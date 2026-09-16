package com.esun.shop.integration;

import com.esun.shop.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Same k6 workload with a test-only external transaction producing a real cycle. */
@TestPropertySource(properties = "stock.redis.enabled=false")
class Phase25K6DeadlockAcceptance extends Phase25K6Acceptance {
    @SpyBean ProductRepository products;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final AtomicInteger realDeadlocks = new AtomicInteger();
    private Future<?> opponent;

    @Override String modeSuffix() { return "-controlled-deadlock"; }

    @Override void prepareLoad() throws Exception {
        CountDownLatch yLocked = new CountDownLatch(1);
        CountDownLatch xLocked = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        for (int i = 0; i < 30; i++) db.update(
                "INSERT INTO product(product_id,product_name,price,quantity) VALUES (?,?,10,10)", "K6WEIGHT" + i, "weight");
        doAnswer(invocation -> {
            try {
                Object result = invocation.callRealMethod();
                if ("P001".equals(invocation.getArgument(0)) && first.compareAndSet(true, false)) {
                    xLocked.countDown();
                    assertThat(contenderStarted.await(20, TimeUnit.SECONDS)).isTrue();
                }
                return result;
            } catch (Throwable failure) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SQLException sql && sql.getErrorCode() == 1213) {
                        realDeadlocks.incrementAndGet(); break;
                    }
                }
                throw failure;
            }
        }).when(products).decreaseStock(anyString(), anyInt());
        opponent = pool.submit(() -> {
            try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
                connection.setAutoCommit(false);
                try {
                    try (var update = connection.prepareStatement("UPDATE product SET quantity=quantity+1 WHERE product_id=?")) {
                        for (int i = 0; i < 30; i++) { update.setString(1, "K6WEIGHT" + i); update.executeUpdate(); }
                        update.setString(1, "P002"); update.executeUpdate();
                        yLocked.countDown();
                        if (!xLocked.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("k6 did not acquire X");
                        contenderStarted.countDown();
                        update.setString(1, "P001"); update.executeUpdate();
                    }
                } finally { connection.rollback(); }
            }
            return null;
        });
        assertThat(yLocked.await(20, TimeUnit.SECONDS)).isTrue();
    }

    @Override void verifyLoad(JsonNode metrics) throws Exception {
        opponent.get(10, TimeUnit.SECONDS);
        assertThat(realDeadlocks.get()).isGreaterThan(0);
        long conflict = count(metrics, "orders_concurrent_conflict");
        if (attempts == 1) {
            assertThat(conflict).isEqualTo(realDeadlocks.get());
            assertThat(orders.getRetryCount()).isZero();
        } else {
            assertThat(conflict).isZero();
            assertThat(orders.getRetryCount()).isGreaterThan(0);
        }
        System.out.printf("PHASE25_K6_DEADLOCK attempts=%d mysql1213=%d httpConflicts=%d retries=%d%n",
                attempts, realDeadlocks.get(), conflict, orders.getRetryCount());
    }

    @AfterEach void stopOpponent() throws Exception {
        pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS);
    }
}
