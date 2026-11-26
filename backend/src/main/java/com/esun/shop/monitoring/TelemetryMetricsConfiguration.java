package com.esun.shop.monitoring;

import com.esun.shop.service.StockCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Registers the business/reliability meters at startup so Prometheus exports them as 0 before the first event.
 * A counter created lazily on its first increment is absent from a scrape until then, which makes rate()-based alert
 * rules silently evaluate to nothing instead of "no failures".
 */
@Configuration
public class TelemetryMetricsConfiguration {

    public static final String LOCK_RETRY = "shop.orders.lock.retry";
    public static final String LOCK_EXHAUSTED = "shop.orders.lock.exhausted";
    public static final String STOCK_CACHE_DEGRADED = "shop.stock.cache.degraded";
    public static final String OLLAMA_UP = "shop.llm.ollama.up";

    @Bean
    MeterBinder businessCounters() {
        return registry -> {
            for (String name : new String[] {"shop.orders.success", "shop.orders.failure", "shop.payments.failure",
                    LOCK_RETRY, LOCK_EXHAUSTED}) {
                Counter.builder(name).register(registry);
            }
        };
    }

    /** 1 while the Redis stock path is latched to DB-only after a failure, 0 otherwise (including disabled). */
    @Bean
    MeterBinder stockCacheDegradedGauge(StockCacheService stockCache) {
        return registry -> Gauge.builder(STOCK_CACHE_DEGRADED, stockCache, s -> {
                    var snapshot = s.dependencySnapshot();
                    return snapshot != null && snapshot.mode() == StockCacheService.DependencyMode.LATCHED_DB_ONLY ? 1 : 0;
                })
                .register(registry);
    }

    /**
     * 1 while Ollama answers and has both configured models, 0 otherwise. Registered only when Ollama is the selected
     * LLM provider (same default as {@code llm.provider}), so a Claude-backed deployment exports no series and the
     * OllamaUnavailable alert stays silent instead of firing on a dependency that is not in use.
     */
    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
    MeterBinder ollamaAvailabilityGauge(
            @Qualifier("ollamaHealthIndicator") HealthIndicator ollamaHealth,
            @Value("${management.metrics.ollama.probe-cache-seconds:30}") long cacheSeconds) {
        CachedHealthGauge gauge = new CachedHealthGauge(ollamaHealth, Duration.ofSeconds(cacheSeconds), System::nanoTime);
        return registry -> Gauge.builder(OLLAMA_UP, gauge, CachedHealthGauge::value)
                .description("1 if Ollama is reachable with the configured models, 0 otherwise")
                .register(registry);
    }
}
