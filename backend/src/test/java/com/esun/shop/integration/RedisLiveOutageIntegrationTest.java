package com.esun.shop.integration;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.model.PayStatus;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.OrderTransactionService;
import com.esun.shop.service.StockCacheService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Live dependency outage; only this test's disposable Redis is paused. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"stock.redis.enabled=true", "spring.data.redis.timeout=200ms",
        "spring.data.redis.connect-timeout=200ms", "stock.redis.audit-interval-ms=3600000",
        "spring.datasource.hikari.maximum-pool-size=30"})
class RedisLiveOutageIntegrationTest extends AbstractMySqlIntegrationTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    static { REDIS.start(); }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired StringRedisTemplate redis;
    @Autowired StockCacheService cache;
    @Autowired OrderTransactionService transactions;
    @Autowired com.esun.shop.repository.ProductRepository products;
    @Autowired com.esun.shop.repository.OrderRepository orders;

    @Test
    void twentyHttpOrdersSurviveLivePauseAndRecoverFromDatabaseSnapshot() throws Exception {
        String product = "LIVE" + UUID.randomUUID().toString().substring(0, 10);
        String zero = product + "Z";
        db.update("INSERT INTO product(product_id,product_name,price,quantity) VALUES (?,?,10,100)", product, product);
        db.update("INSERT INTO product(product_id,product_name,price,quantity) VALUES (?,?,10,0)", zero, zero);
        cache.preload();
        var auth = http.postForEntity("/api/auth/register", Map.of(
                "email", product + "@example.com", "password", "outage-test-password"), JsonNode.class);
        assertThat(auth.getStatusCode().value()).isEqualTo(200);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(auth.getBody().path("data").path("token").asText());
        long initialOrders = db.queryForObject("SELECT COUNT(*) FROM shop_order", Long.class);
        place(request(product), headers);
        assertThat(redis.opsForValue().get("stock:" + product)).isEqualTo("99");

        var pool = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        List<CreateOrderRequest> requests = new ArrayList<>();
        List<Future<JsonNode>> results = new ArrayList<>();
        List<JsonNode> responses = new ArrayList<>();
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThat(REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec()
                    .getState().getPaused()).isTrue();
            for (int i = 0; i < 20; i++) {
                CreateOrderRequest request = request(product);
                requests.add(request);
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return place(request, headers);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<JsonNode> result : results) responses.add(result.get(30, TimeUnit.SECONDS));
            // All HTTP requests completed while Redis was still paused.
            assertStock(product, 79);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM shop_order", Long.class)).isEqualTo(initialOrders + 21);
            assertThat(db.queryForObject("SELECT SUM(quantity) FROM order_detail WHERE product_id=?", Long.class, product)).isEqualTo(21);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // Drain any ambiguous timed-out Redis commands before reading settled stock.
        try (var connection = redis.getConnectionFactory().getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
        String cacheBefore = redis.opsForValue().get("stock:" + product);
        assertThat(place(requests.get(0), headers)).isEqualTo(responses.get(0));
        assertStock(product, 79); // replay neither creates nor reserves again
        place(request(product), headers);
        assertStock(product, 78);
        assertThat(redis.opsForValue().get("stock:" + product)).isEqualTo(cacheBefore);
        Map<String, Long> driftBefore = cache.audit();

        // Writers are drained. Restore ALL app keys from actual MySQL, including zero.
        reconcile();
        assertThat(redis.opsForValue().get("stock:" + zero)).isEqualTo("0");
        assertThat(cache.audit()).isEmpty();
        place(request(product), headers);
        assertStock(product, 77);
        assertThat(redis.opsForValue().get("stock:" + product)).isEqualTo("78");
        assertThat(cache.audit()).containsEntry(product, 77L); // latch survives reconnection/reconciliation
        reconcile();

        // Fresh service instance models process-local latch reset, using the real DB proxy.
        StockCacheService restartedCache = new StockCacheService(redis, products, true);
        restartedCache.preload();
        OrderService restartedOrders = new OrderService(transactions, restartedCache, orders);
        assertThat(restartedOrders.createOrder(request(product))).isNotBlank();
        assertStock(product, 76);
        assertThat(restartedCache.audit()).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM shop_order", Long.class)).isEqualTo(initialOrders + 24);
        assertThat(db.queryForObject("SELECT SUM(quantity) FROM order_detail WHERE product_id=?", Long.class, product)).isEqualTo(24);
        System.out.printf("PHASE25_LIVE_OUTAGE httpDuringPause=20/20 finalStock=76 newOrders=24 driftBefore=%s finalAudit=empty%n", driftBefore);
    }

    private JsonNode place(CreateOrderRequest request, HttpHeaders headers) {
        var response = http.postForEntity("/api/orders", new HttpEntity<>(request, headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).as("requestId=%s body=%s", request.getRequestId(), response.getBody()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void reconcile() {
        products.findAllStock().forEach(p -> redis.opsForValue().set("stock:" + p.getProductId(), String.valueOf(p.getQuantity())));
    }

    private void assertStock(String product, int quantity) {
        assertThat(db.queryForObject("SELECT quantity FROM product WHERE product_id=?", Integer.class, product)).isEqualTo(quantity);
    }

    private CreateOrderRequest request(String product) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(product); item.setQuantity(1);
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(UUID.randomUUID().toString()); request.setMemberId("LIVE-OUTAGE");
        request.setPayStatus(PayStatus.PENDING); request.setItems(List.of(item));
        return request;
    }
}
