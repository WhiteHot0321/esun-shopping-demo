package com.esun.shop.monitoring;

import com.esun.shop.service.StockCacheService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

/** Optional-dependency observations that never participate in readiness or liveness. */
@Configuration
public class ExternalDependenciesHealthConfiguration {

    @Bean
    HealthIndicator redisHealthIndicator(StockCacheService stockCache, StringRedisTemplate redis) {
        return () -> {
            StockCacheService.DependencyMode mode = stockCache.dependencySnapshot().mode();
            if (mode == StockCacheService.DependencyMode.DISABLED) {
                return Health.up()
                        .withDetail("mode", "DISABLED")
                        .withDetail("connectivity", "NOT_PROBED")
                        .build();
            }

            String connectivity;
            try (var connection = redis.getConnectionFactory().getConnection()) {
                connectivity = "PONG".equals(connection.ping()) ? "AVAILABLE" : "UNEXPECTED_RESPONSE";
            } catch (RuntimeException ex) {
                connectivity = "UNAVAILABLE";
            }

            if (mode == StockCacheService.DependencyMode.LATCHED_DB_ONLY) {
                return Health.down()
                        .withDetail("mode", "LATCHED_DB_ONLY")
                        .withDetail("connectivity", connectivity)
                        .build();
            }
            if (!"AVAILABLE".equals(connectivity)) {
                return Health.down()
                        .withDetail("mode", "CONNECTION_FAILED")
                        .withDetail("connectivity", connectivity)
                        .build();
            }
            return Health.up()
                    .withDetail("mode", "ENABLED")
                    .withDetail("connectivity", connectivity)
                    .build();
        };
    }

    @Bean
    HealthIndicator ollamaHealthIndicator(
            @Value("${llm.provider:ollama}") String provider,
            @Value("${llm.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${llm.ollama.chat-model:llama3.1}") String chatModel,
            @Value("${llm.ollama.embed-model:nomic-embed-text}") String embedModel,
            @Value("${management.health.dependencies.timeout-ms:500}") int timeoutMs) {
        if (!"ollama".equalsIgnoreCase(provider)) {
            return () -> Health.up().withDetail("mode", "NOT_SELECTED").build();
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();

        return () -> {
            try {
                Map<?, ?> response = client.get().uri("/api/tags").retrieve().body(Map.class);
                List<?> models = response == null || !(response.get("models") instanceof List<?> values)
                        ? List.of() : values;
                long missing = List.of(chatModel, embedModel).stream()
                        .filter(required -> models.stream().noneMatch(model -> matchesModel(model, required)))
                        .count();
                if (missing > 0) {
                    return Health.down()
                            .withDetail("mode", "REACHABLE_MISSING_MODELS")
                            .withDetail("connectivity", "AVAILABLE")
                            .withDetail("requiredModelCount", 2)
                            .withDetail("missingModelCount", missing)
                            .withDetail("semantics", "TAG_INVENTORY_ONLY")
                            .build();
                }
                return Health.up()
                        .withDetail("mode", "REACHABLE_MODELS_PRESENT")
                        .withDetail("connectivity", "AVAILABLE")
                        .withDetail("requiredModelCount", 2)
                        .withDetail("missingModelCount", 0)
                        .withDetail("semantics", "TAG_INVENTORY_ONLY")
                        .build();
            } catch (ResourceAccessException ex) {
                return Health.down()
                        .withDetail("mode", hasTimeoutCause(ex) ? "TIMEOUT" : "UNAVAILABLE")
                        .withDetail("connectivity", "UNAVAILABLE")
                        .build();
            } catch (RuntimeException ex) {
                return Health.down()
                        .withDetail("mode", "UNAVAILABLE")
                        .withDetail("connectivity", "UNAVAILABLE")
                        .build();
            }
        };
    }

    private static boolean matchesModel(Object value, String required) {
        if (!(value instanceof Map<?, ?> model)) return false;
        Object name = model.get("name");
        Object id = model.get("model");
        return matchesModelName(name, required) || matchesModelName(id, required);
    }

    private static boolean matchesModelName(Object value, String required) {
        if (!(value instanceof String candidate)) return false;
        return candidate.equals(required) || candidate.startsWith(required + ":");
    }

    private static boolean hasTimeoutCause(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException) return true;
        }
        return false;
    }
}
