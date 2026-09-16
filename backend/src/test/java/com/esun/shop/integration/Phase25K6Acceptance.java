package com.esun.shop.integration;

import com.esun.shop.service.OrderService;
import com.esun.shop.service.StockCacheService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Explicit acceptance runner: -Dtest=Phase25K6Acceptance (not part of default suite). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Phase25K6Acceptance extends AbstractMySqlIntegrationTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    static { REDIS.start(); }
    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired StockCacheService cache;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired OrderService orders;
    @Value("${order.retry.max-attempts:3}") int attempts;

    @Test
    void realHttpTwentyWorkersWithStockAudit() throws Exception {
        // Fresh disposable containers only; never changes the user's compose DB.
        db.update("UPDATE product SET quantity=10000 WHERE product_id IN ('P001','P002','P003')");
        if (cache.isEnabled()) {
            for (String id : new String[]{"P001", "P002", "P003"}) redis.delete("stock:" + id);
            cache.preload();
        }
        String mode = "redis-" + cache.isEnabled() + "-attempts-" + attempts + modeSuffix();
        long initialOrders = db.queryForObject("SELECT COUNT(*) FROM shop_order", Long.class);
        Path summary = Path.of("target", "phase25-k6-" + mode + ".json").toAbsolutePath();
        Path output = Path.of("target", "phase25-k6-" + mode + ".log").toAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder("k6", "run", "--summary-export", summary.toString(),
                Path.of("..", "bench", "order-load-test-multiitem.js").toAbsolutePath().toString());
        builder.environment().put("BASE_URL", "http://localhost:" + port);
        builder.environment().put("VUS", "20");
        builder.environment().put("DURATION", "20s");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        prepareLoad();
        Process process = builder.start();
        try {
            assertThat(process.waitFor(90, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        JsonNode metrics = new ObjectMapper().readTree(summary.toFile()).path("metrics");
        long successful = count(metrics, "orders_success_200");
        long total = successful;
        for (String name : new String[]{"orders_conflict_409", "orders_notfound_404", "orders_badrequest_400",
                "orders_servererror_500", "orders_other_status", "orders_no_response"}) total += count(metrics, name);
        assertThat(total).isGreaterThan(20);
        assertThat((double) successful / total).isGreaterThanOrEqualTo(.95);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM shop_order", Long.class)).isEqualTo(initialOrders + successful);
        for (String id : new String[]{"P001", "P002", "P003"}) {
            assertThat(db.queryForObject("SELECT quantity FROM product WHERE product_id=?", Long.class, id)).isEqualTo(10000 - successful);
        }
        if (cache.isEnabled()) assertThat(cache.audit()).isEmpty();
        verifyLoad(metrics);
        System.out.printf("PHASE25_K6 mode=%s total=%d success=%d retries=%d stock=%d audit=%s%n",
                mode, total, successful, orders.getRetryCount(), 10000 - successful, cache.isEnabled() ? "no-drift" : "DB-only");
    }
    String modeSuffix() { return ""; }
    void prepareLoad() throws Exception {}
    void verifyLoad(JsonNode metrics) throws Exception {}
    long count(JsonNode metrics, String name) {
        JsonNode metric = metrics.path(name);
        return metric.has("values") ? metric.path("values").path("count").asLong() : metric.path("count").asLong();
    }
}
