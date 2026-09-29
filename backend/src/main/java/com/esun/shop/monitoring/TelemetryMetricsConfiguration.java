package com.esun.shop.monitoring;

import com.esun.shop.service.StockCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
