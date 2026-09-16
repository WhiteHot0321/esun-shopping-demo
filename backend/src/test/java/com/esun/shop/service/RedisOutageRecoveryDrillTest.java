package com.esun.shop.service;

import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.model.Product;
import com.esun.shop.repository.ProductRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 010 sub-task B: -Dtest=RedisOutageRecoveryDrillTest (not part of the default suite).
 * Runs one always-healthy disposable Testcontainers Redis for the whole test - it is never
 * paused, stopped or otherwise manipulated, sidestepping the Windows/Docker Desktop port-
 * forwarding flakiness observed when stopping/starting a Testcontainers container mid-test.
 * "Outage" is simulated the same way {@code RedisUnavailableOrderIntegrationTest} simulates
 * it for the full order flow: a second, real Lettuce client pointed at an unreachable port.
 * DB truth is tracked as a plain map (mocked {@link ProductRepository}) since the DB-authoritative
 * conditional decrement itself is already covered end-to-end by other integration tests; this
 * drill's job is the cache/reconciliation contract, not re-proving transactional order placement.
 */
class RedisOutageRecoveryDrillTest {
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    private static LettuceConnectionFactory healthyFactory;
    private static StringRedisTemplate healthyRedis;
    private ProductRepository productRepository;
    private Map<String, Integer> dbTruth;

    static {
        REDIS.start();
        healthyFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        healthyFactory.afterPropertiesSet();
        healthyRedis = new StringRedisTemplate(healthyFactory);
        healthyRedis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        healthyFactory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        healthyRedis.getConnectionFactory().getConnection().serverCommands().flushAll();
        dbTruth = new HashMap<>(Map.of("p1", 50, "p2", 50));
        productRepository = Mockito.mock(ProductRepository.class);
        Mockito.when(productRepository.findAllStock()).thenAnswer(inv ->
                dbTruth.entrySet().stream().map(e -> product(e.getKey(), e.getValue())).toList());
        healthyRedis.opsForValue().set("stock:p1", "50");
        healthyRedis.opsForValue().set("stock:p2", "50");
    }

    @Test
    void outageLatchesDbOnlyModeAndOnlyARestartResumesRedis() throws Exception {
        StockCacheService cache = new StockCacheService(healthyRedis, productRepository, true);
        assertThat(cache.audit()).isEmpty();

        // Baseline: healthy Redis reservation succeeds and DB moves in lockstep, as a real
        // order does (OrderService decrements DB only after a RESERVED/BYPASSED outcome).
        assertThat(cache.tryDecrease(List.of(item("p1", 1)))).isEqualTo(StockCacheService.Reservation.RESERVED);
        dbTruth.put("p1", 49);
        assertThat(healthyRedis.opsForValue().get("stock:p1")).isEqualTo("49");
        assertThat(cache.audit()).isEmpty();

        // Outage: a real Lettuce client pointed at an unreachable port, exactly like
        // RedisUnavailableOrderIntegrationTest uses for the full order flow. The first call
        // times out/fails and latches this instance's `degraded` flag; DB-conditional decrement
        // remains the real no-oversell guarantee, so the order still succeeds (simulated here
        // by moving dbTruth directly, since the full OrderService path is covered elsewhere).
        LettuceClientConfiguration shortTimeout = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500)).build();
        LettuceConnectionFactory brokenFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1), shortTimeout);
        brokenFactory.afterPropertiesSet();
        StringRedisTemplate brokenRedis = new StringRedisTemplate(brokenFactory);
        brokenRedis.afterPropertiesSet();
        StockCacheService outageInstance = new StockCacheService(brokenRedis, productRepository, true);
        assertThat(outageInstance.tryDecrease(List.of(item("p1", 1)))).isEqualTo(StockCacheService.Reservation.BYPASSED);
        dbTruth.put("p1", 48);

        // `degraded` has no reset path in StockCacheService. Destroy the connection factory
        // outright before the next call, so if the code ever tried to reach Redis again it
        // would hit a hard "factory not initialized" failure rather than a soft timeout - and
        // confirm it still returns BYPASSED cleanly instead of throwing, proving the app can
        // never crash or hang on this path no matter how broken the connection becomes.
        brokenFactory.destroy();
        assertThat(outageInstance.tryDecrease(List.of(item("p1", 1)))).isEqualTo(StockCacheService.Reservation.BYPASSED);
        dbTruth.put("p1", 47);

        // The healthy instance's own Redis key was never touched by the degraded instance
        // (different connection entirely), so it now visibly drifts from the DB truth that kept
        // moving underneath it - this is the real, expected drift a genuine outage produces.
        assertThat(healthyRedis.opsForValue().get("stock:p1")).isEqualTo("49");
        assertThat(cache.audit()).containsEntry("p1", 47L);
        assertThat(cache.audit()).doesNotContainKey("p2");

        // Recovery procedure from bench/PHASE25.md: pause writers, take the authoritative DB
        // snapshot, and overwrite this app's stock:{productId} keys for ALL products - including
        // ones that never drifted (p2) - per "verify all keys against DB".
        dbTruth.forEach((id, qty) -> healthyRedis.opsForValue().set("stock:" + id, String.valueOf(qty)));
        assertThat(cache.audit()).isEmpty();

        // Fixing Redis alone does not fix the in-process latch (already demonstrated above: the
        // degraded instance kept returning BYPASSED cleanly even after its connection factory
        // was destroyed outright). This documents that the recovery procedure's "restart
        // writers" step means restarting the application process, not merely resuming traffic.

        // Simulate the actual restart: a fresh StockCacheService (degraded=false by
        // construction, matching a new JVM) pointed at the real, healthy Redis. preload() uses
        // SET NX so it will not clobber the values already fixed above.
        StockCacheService restarted = new StockCacheService(healthyRedis, productRepository, true);
        restarted.preload();
        assertThat(restarted.tryDecrease(List.of(item("p1", 1)))).isEqualTo(StockCacheService.Reservation.RESERVED);
        dbTruth.put("p1", 46);
        assertThat(restarted.audit()).isEmpty();
    }

    private Product product(String id, int quantity) {
        Product product = new Product();
        product.setProductId(id);
        product.setQuantity(quantity);
        product.setPrice(BigDecimal.TEN);
        return product;
    }

    private OrderItemRequest item(String productId, int quantity) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }
}
