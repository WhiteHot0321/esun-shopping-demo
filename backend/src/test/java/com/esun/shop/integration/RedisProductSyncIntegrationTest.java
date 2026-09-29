package com.esun.shop.integration;

import com.esun.shop.dto.BulkProductRequest;
import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.model.PayStatus;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.ProductService;
import com.esun.shop.service.StockCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Redis stock counters are preloaded only at startup, so product changes made afterwards must be applied to Redis
 * once their transaction commits. Without that, a product created at runtime latched the whole service to DB-only on its
 * first order, and a sold-out product stayed unsellable (409) after a restock until the next restart.
 */
@TestPropertySource(properties = {"stock.redis.enabled=true", "stock.redis.audit-interval-ms=3600000"})
class RedisProductSyncIntegrationTest extends AbstractMySqlIntegrationTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    static { REDIS.start(); }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static final String SELLER = "sync-seller@example.com";

    @Autowired ProductService products;
    @Autowired OrderService orders;
    @Autowired StockCacheService cache;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate db;

    private String create(int quantity) {
        String id = "S" + UUID.randomUUID().toString().substring(0, 12);
        CreateProductRequest request = new CreateProductRequest();
        request.setProductId(id);
        request.setProductName("Sync " + id);
        request.setPrice(new BigDecimal("10.00"));
        request.setQuantity(quantity);
        products.createProduct(request, SELLER);
        return id;
    }

    private String order(String product, int quantity) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(product);
        item.setQuantity(quantity);
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setMemberId("REDIS-TEST"); // pre-seeded with a default address by the base class
        request.setPayStatus(PayStatus.PENDING);
        request.setItems(List.of(item));
        return orders.createOrder(request);
    }

    private int dbStock(String product) {
        return db.queryForObject("SELECT quantity FROM product WHERE product_id=?", Integer.class, product);
    }

    private String redisStock(String product) {
        return redis.opsForValue().get("stock:" + product);
    }

    @Test
    void productCreatedAfterStartupIsSeededSoTheFirstOrderDoesNotLatchDbOnlyMode() {
        String product = create(3);
        assertThat(redisStock(product)).isEqualTo("3");

        assertThat(order(product, 2)).isNotBlank();

        assertThat(dbStock(product)).isEqualTo(1);
        assertThat(redisStock(product)).isEqualTo("1");
        assertThat(cache.dependencySnapshot().mode()).isEqualTo(StockCacheService.DependencyMode.ENABLED);
        assertThat(cache.audit()).doesNotContainKey(product);
    }

    @Test
    void restockAfterSelloutMakesTheProductSellableAgain() {
        String product = create(2);
        order(product, 2);
        assertThat(dbStock(product)).isZero();
        assertThat(redisStock(product)).isEqualTo("0");

        products.restockOwnedProduct(product, 5, SELLER);

        assertThat(dbStock(product)).isEqualTo(5);
        assertThat(redisStock(product)).isEqualTo("5");
        assertThat(order(product, 1)).isNotBlank();
        assertThat(dbStock(product)).isEqualTo(4);
        assertThat(redisStock(product)).isEqualTo("4");
        assertThat(cache.audit()).doesNotContainKey(product);
    }

    @Test
    void bulkRestockIsAppliedToEveryProduct() {
        String first = create(1);
        String second = create(4);
        BulkProductRequest request = new BulkProductRequest();
        request.setProductIds(List.of(first, second));
        request.setAction(BulkProductRequest.Action.RESTOCK);
        request.setAmount(3);

        products.bulkManageOwnedProducts(request, SELLER);

        assertThat(redisStock(first)).isEqualTo("4");
        assertThat(redisStock(second)).isEqualTo("7");
        assertThat(cache.audit()).doesNotContainKeys(first, second);
    }

    @Test
    void restockSeedsAMissingCounterFromTheDatabaseInsteadOfCreatingItAtTheRestockAmount() {
        String product = create(6);
        redis.delete("stock:" + product);

        products.restockOwnedProduct(product, 4, SELLER);

        assertThat(dbStock(product)).isEqualTo(10);
        assertThat(redisStock(product)).isEqualTo("10");
    }
}
