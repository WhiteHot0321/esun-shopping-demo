package com.esun.shop.service;

import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.model.Product;
import com.esun.shop.repository.ProductRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StockCacheServiceIntegrationTest {
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private ProductRepository productRepository;
    private StockCacheService stockCache;

    static {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        productRepository = Mockito.mock(ProductRepository.class);
        stockCache = new StockCacheService(redis, productRepository, true);
    }

    @Test
    void tryDecrease_isAtomicForMultiItemOrderAndCompensateRestoresStock() {
        redis.opsForValue().set("stock:P001", "3");
        redis.opsForValue().set("stock:P002", "1");
        List<OrderItemRequest> items = List.of(item("P001", 2), item("P002", 2));

        assertThat(stockCache.tryDecrease(items)).isEqualTo(StockCacheService.Reservation.INSUFFICIENT);
        assertThat(redis.opsForValue().get("stock:P001")).isEqualTo("3");
        assertThat(redis.opsForValue().get("stock:P002")).isEqualTo("1");

        assertThat(stockCache.tryDecrease(List.of(item("P001", 2), item("P002", 1)))).isEqualTo(StockCacheService.Reservation.RESERVED);
        assertThat(redis.opsForValue().get("stock:P001")).isEqualTo("1");
        assertThat(redis.opsForValue().get("stock:P002")).isEqualTo("0");
        stockCache.compensate(List.of(item("P001", 2), item("P002", 1)));
        assertThat(redis.opsForValue().get("stock:P001")).isEqualTo("3");
        assertThat(redis.opsForValue().get("stock:P002")).isEqualTo("1");
    }

    @Test
    void audit_reportsRedisDbDrift() {
        Product p = new Product();
        p.setProductId("P001");
        p.setQuantity(5);
        p.setPrice(BigDecimal.ONE);
        Mockito.when(productRepository.findAllStock()).thenReturn(List.of(p));
        redis.opsForValue().set("stock:P001", "4");

        assertThat(stockCache.audit()).containsExactly(Map.entry("P001", 5L));
    }

    @Test
    void preloadIncludesZeroAndDoesNotOverwriteReservations() {
        Product zero = product("ZERO", 0);
        Product live = product("LIVE", 10);
        Mockito.when(productRepository.findAllStock()).thenReturn(List.of(zero, live));
        redis.opsForValue().set("stock:LIVE", "7");
        stockCache.preloadOnStartup();
        assertThat(redis.opsForValue().get("stock:ZERO")).isEqualTo("0");
        assertThat(redis.opsForValue().get("stock:LIVE")).isEqualTo("7");
        assertThat(stockCache.audit()).containsExactly(Map.entry("LIVE", 10L));
        redis.opsForValue().set("stock:ZERO", "1");
        assertThat(stockCache.audit()).containsEntry("ZERO", 0L);
        stockCache.auditScheduled();
    }

    @Test
    void missingKeyLatchesBypassWithoutPartialDecrement() {
        redis.opsForValue().set("stock:LIVE", "7");
        assertThat(stockCache.tryDecrease(List.of(item("LIVE", 1), item("MISSING", 1))))
                .isEqualTo(StockCacheService.Reservation.BYPASSED);
        assertThat(stockCache.tryDecrease(List.of(item("LIVE", 1))))
                .isEqualTo(StockCacheService.Reservation.BYPASSED);
        assertThat(redis.opsForValue().get("stock:LIVE")).isEqualTo("7");
    }

    @Test
    void compensationMissingKeyDoesNotPartiallyIncrementAndLatchesBypass() {
        redis.opsForValue().set("stock:LIVE", "7");
        stockCache.compensate(List.of(item("LIVE", 1), item("MISSING", 1)));
        assertThat(redis.opsForValue().get("stock:LIVE")).isEqualTo("7");
        assertThat(stockCache.tryDecrease(List.of(item("LIVE", 1))))
                .isEqualTo(StockCacheService.Reservation.BYPASSED);
    }

    @Test
    void disabledModeDoesNotTouchRedisAndScheduledAuditIsSafe() {
        StockCacheService disabled = new StockCacheService(redis, productRepository, false);
        disabled.preloadOnStartup(); disabled.preload(); disabled.compensate(List.of(item("MISSING", 1)));
        disabled.auditScheduled();
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.audit()).isEmpty();
        assertThat(disabled.tryDecrease(List.of(item("MISSING", 1)))).isEqualTo(StockCacheService.Reservation.BYPASSED);
        assertThat(redis.opsForValue().get("stock:MISSING")).isNull();
    }

    @Test
    void auditSchedulerHandlesUnavailableDatabaseWithoutThrowing() {
        Mockito.when(productRepository.findAllStock()).thenThrow(new IllegalStateException("DB unavailable"));
        stockCache.auditScheduled();
        stockCache.preloadOnStartup();
    }

    private Product product(String id, int quantity) {
        Product product = new Product(); product.setProductId(id); product.setQuantity(quantity); return product;
    }

    private OrderItemRequest item(String productId, int quantity) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }
}
