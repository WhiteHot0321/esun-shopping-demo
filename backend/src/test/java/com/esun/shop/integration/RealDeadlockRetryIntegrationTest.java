package com.esun.shop.integration;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.model.PayStatus;
import com.esun.shop.repository.ProductRepository;
import com.esun.shop.service.ConcurrentOrderException;
import com.esun.shop.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Real InnoDB cycles; the spy only coordinates timing, never fabricates exceptions. */
@TestPropertySource(properties = {"stock.redis.enabled=false", "spring.datasource.hikari.maximum-pool-size=30"})
class RealDeadlockRetryIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired OrderService orders;
    @Autowired JdbcTemplate db;
    @SpyBean ProductRepository products;
    @Value("${order.retry.max-attempts:3}") int attempts;

    record Pair(String x, String y, String ballastPrefix, CountDownLatch yLocked,
                CountDownLatch xLocked, CountDownLatch contenderStarted, AtomicBoolean first) {}

    @Test
    void twentyCallersRecoverFromRealDeadlocksOrFailWithOneAttempt() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 6);
        Map<String, Pair> pairs = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            String prefix = "DL" + run + String.format("%02d", i);
            Pair pair = new Pair(prefix + "X", prefix + "Y", prefix + "B",
                    new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1), new AtomicBoolean(true));
            pairs.put(pair.x(), pair);
            seed(pair.x()); seed(pair.y());
            for (int j = 0; j < 30; j++) seed(pair.ballastPrefix() + j);
        }
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            Object result = invocation.callRealMethod();
            Pair pair = pairs.get(id);
            if (pair != null && pair.first().compareAndSet(true, false)) {
                pair.xLocked().countDown();
                assertThat(pair.contenderStarted().await(20, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(products).decreaseStock(anyString(), anyInt());

        ExecutorService pool = Executors.newFixedThreadPool(40);
        List<Future<?>> opponents = new ArrayList<>();
        List<Future<Boolean>> clients = new ArrayList<>();
        long before = orders.getRetryCount();
        try {
            for (Pair pair : pairs.values()) {
                opponents.add(pool.submit(() -> {
                    try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
                        connection.setAutoCommit(false);
                        try {
                            // Make the contender heavier so InnoDB selects the order
                            // transaction as victim. All contender writes roll back.
                            try (var weight = connection.prepareStatement("UPDATE product SET quantity=quantity+1 WHERE product_id = ?")) {
                                for (int j = 0; j < 30; j++) {
                                    weight.setString(1, pair.ballastPrefix() + j); weight.executeUpdate();
                                }
                            }
                            try (var lock = connection.prepareStatement("UPDATE product SET quantity=quantity+1 WHERE product_id=?")) {
                                lock.setString(1, pair.y()); lock.executeUpdate();
                                pair.yLocked().countDown();
                                if (!pair.xLocked().await(20, TimeUnit.SECONDS)) throw new IllegalStateException("order did not lock X");
                                pair.contenderStarted().countDown();
                                lock.setString(1, pair.x()); lock.executeUpdate();
                            }
                        } finally { connection.rollback(); }
                    }
                    return null;
                }));
                clients.add(pool.submit(() -> {
                    assertThat(pair.yLocked().await(20, TimeUnit.SECONDS)).isTrue();
                    CreateOrderRequest request = request(pair);
                    try {
                        assertThat(orders.createOrder(request)).isNotBlank();
                        assertThat(attempts).isGreaterThan(1);
                        return true;
                    } catch (ConcurrentOrderException failure) {
                        assertThat(attempts).isEqualTo(1);
                        SQLException sql = sqlCause(failure);
                        assertThat((Object) sql).isNotNull();
                        assertThat(sql.getErrorCode()).isEqualTo(1213);
                        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_request WHERE request_id=?", Integer.class, request.getRequestId())).isZero();
                        return false;
                    }
                }));
            }
            int succeeded = 0;
            for (Future<Boolean> client : clients) if (client.get(60, TimeUnit.SECONDS)) succeeded++;
            for (Future<?> opponent : opponents) opponent.get(30, TimeUnit.SECONDS);
            assertThat(succeeded).isEqualTo(attempts == 1 ? 0 : 20);
            assertThat(orders.getRetryCount() - before).isEqualTo(attempts == 1 ? 0 : 20);
            for (Pair pair : pairs.values()) {
                assertThat(db.queryForObject("SELECT quantity FROM product WHERE product_id=?", Integer.class, pair.x())).isEqualTo(attempts == 1 ? 10 : 9);
                assertThat(db.queryForObject("SELECT quantity FROM product WHERE product_id=?", Integer.class, pair.y())).isEqualTo(attempts == 1 ? 10 : 9);
            }
            System.out.printf("PHASE25_REAL_DEADLOCK attempts=%d callers=20 succeeded=%d retries=%d%n", attempts, succeeded, orders.getRetryCount() - before);
        } finally {
            pool.shutdownNow(); pool.awaitTermination(20, TimeUnit.SECONDS);
        }
    }

    void seed(String id) { db.update("INSERT INTO product(product_id,product_name,price,quantity) VALUES (?,?,10,10)", id, id); }
    CreateOrderRequest request(Pair pair) {
        List<OrderItemRequest> items = new ArrayList<>();
        for (String id : List.of(pair.x(), pair.y())) {
            OrderItemRequest item = new OrderItemRequest(); item.setProductId(id); item.setQuantity(1); items.add(item);
        }
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(UUID.randomUUID().toString()); request.setMemberId("DEADLOCK-TEST");
        request.setPayStatus(PayStatus.PENDING); request.setItems(items); return request;
    }
    SQLException sqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof SQLException sql) return sql;
        return null;
    }
}
