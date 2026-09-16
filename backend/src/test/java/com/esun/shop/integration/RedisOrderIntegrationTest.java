package com.esun.shop.integration;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.model.PayStatus;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.StockCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties = {"stock.redis.enabled=true", "stock.redis.audit-interval-ms=3600000"})
class RedisOrderIntegrationTest extends AbstractMySqlIntegrationTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    static { REDIS.start(); }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired OrderService orders;
    @Autowired StockCacheService cache;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate db;

    String seed(int stock) {
        String id = "R" + UUID.randomUUID().toString().substring(0, 12);
        db.update("INSERT INTO product(product_id,product_name,price,quantity) VALUES (?,?,10,?)", id, id, stock);
        cache.preload();
        return id;
    }

    CreateOrderRequest request(String key, String product, int quantity) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(product);
        item.setQuantity(quantity);
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(key);
        request.setMemberId("REDIS-TEST");
        request.setPayStatus(PayStatus.PENDING);
        request.setItems(List.of(item));
        return request;
    }

    void stockEquals(String product, int expected) {
        assertThat(db.queryForObject("SELECT quantity FROM product WHERE product_id=?", Integer.class, product)).isEqualTo(expected);
        assertThat(redis.opsForValue().get("stock:" + product)).isEqualTo(String.valueOf(expected));
        assertThat(cache.audit()).doesNotContainKey(product);
    }

    @Test
    void twentySameKeysForLastUnitAndReplayAfterSellout() throws Exception {
        String product = seed(1);
        String key = UUID.randomUUID().toString();
        List<String> ids = concurrent(() -> orders.createOrder(request(key, product, 1)));
        assertThat(ids).hasSize(20).containsOnly(ids.get(0));
        assertThat(orders.createOrder(request(key, product, 1))).isEqualTo(ids.get(0));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_detail WHERE product_id=?", Integer.class, product)).isEqualTo(1);
        stockEquals(product, 0);
    }

    @Test
    void twentyDistinctKeysDeductOnceEachWithoutDrift() throws Exception {
        String product = seed(20);
        List<String> ids = concurrent(() -> orders.createOrder(request(UUID.randomUUID().toString(), product, 1)));
        assertThat(ids).hasSize(20).doesNotHaveDuplicates();
        stockEquals(product, 0);
    }

    @Test
    void realDatabaseFailureRollsBackClaimOrderAndCompensatesRedis() {
        String product = seed(3);
        String key = UUID.randomUUID().toString();
        db.execute("ALTER TABLE order_detail ADD CONSTRAINT phase25_test_failure "
                + "CHECK (product_id <> '" + product + "' OR quantity < 2)");
        try {
            assertThatThrownBy(() -> orders.createOrder(request(key, product, 2))).isInstanceOf(DataAccessException.class);
            stockEquals(product, 3);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM order_request WHERE request_id=?", Integer.class, key)).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM order_detail WHERE product_id=?", Integer.class, product)).isZero();
        } finally {
            db.execute("ALTER TABLE order_detail DROP CHECK phase25_test_failure");
        }
        assertThat(orders.createOrder(request(key, product, 2))).isNotBlank();
        stockEquals(product, 1);
    }

    @Test
    void repeatedProductDemandIsRejectedWithoutAnyDecrement() {
        String product = seed(5);
        CreateOrderRequest request = request(UUID.randomUUID().toString(), product, 3);
        request.setItems(List.of(request.getItems().get(0), request.getItems().get(0)));
        assertThatThrownBy(() -> orders.createOrder(request)).isInstanceOf(com.esun.shop.exception.BusinessException.class);
        stockEquals(product, 5);
    }

    List<String> concurrent(Callable<String> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return call.call();
            }));
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
