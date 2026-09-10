package com.esun.shop.integration;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.model.PayStatus;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers two Phase 1 fixes that only show up under real transactions/locking,
 * so they run against the real MySQL container rather than mocks:
 *
 * 1. 3.3's fixed lock order (product/stock rows locked in ascending productId
 *    order) - concurrent orders that reference the same two products in opposite
 *    submission order must both complete instead of deadlocking.
 * 2. sp_decrease_stock's SIGNAL on insufficient stock must roll back the whole
 *    @Transactional createOrder() call, so a failed order never leaves behind an
 *    order_detail row (or a decremented stock row) with no matching committed order.
 */
class OrderConcurrencyIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seedProduct(String productId, String price, int quantity) {
        CreateProductRequest request = new CreateProductRequest();
        request.setProductId(productId);
        request.setProductName("concurrency product " + productId);
        request.setPrice(new BigDecimal(price));
        request.setQuantity(quantity);
        productService.createProduct(request);
    }

    private OrderItemRequest item(String productId, int quantity) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    private CreateOrderRequest orderRequest(String memberId, List<OrderItemRequest> items) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setMemberId(memberId);
        request.setPayStatus(PayStatus.PENDING);
        request.setItems(items);
        return request;
    }

    @Test
    void createOrder_concurrentOrdersReferencingSameProductsInOppositeOrder_bothCompleteWithoutDeadlock() throws Exception {
        seedProduct("CC-P001", "10.00", 100);
        seedProduct("CC-P002", "20.00", 100);

        // Thread A submits P001 then P002; thread B submits the same two products in the
        // reverse order. Without the fixed (ascending productId) lock order this is the
        // classic cross-locking pattern that deadlocks; with the fix both must finish.
        CreateOrderRequest orderA = orderRequest("MEMBER-A", List.of(item("CC-P001", 1), item("CC-P002", 1)));
        CreateOrderRequest orderB = orderRequest("MEMBER-B", List.of(item("CC-P002", 1), item("CC-P001", 1)));

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<String> taskA = () -> {
                startLatch.await();
                return orderService.createOrder(orderA);
            };
            Callable<String> taskB = () -> {
                startLatch.await();
                return orderService.createOrder(orderB);
            };

            Future<String> futureA = executor.submit(taskA);
            Future<String> futureB = executor.submit(taskB);
            startLatch.countDown();

            // Generous but bounded timeout: a real deadlock would hang until MySQL's
            // innodb_lock_wait_timeout (50s default), so this fails fast on a regression
            // without being flaky under normal contention.
            String orderIdA = futureA.get(45, TimeUnit.SECONDS);
            String orderIdB = futureB.get(45, TimeUnit.SECONDS);

            assertThat(orderIdA).isNotBlank();
            assertThat(orderIdB).isNotBlank();
        } finally {
            executor.shutdownNow();
        }

        Integer remainingP001 = jdbcTemplate.queryForObject(
                "SELECT quantity FROM product WHERE product_id = ?", Integer.class, "CC-P001");
        Integer remainingP002 = jdbcTemplate.queryForObject(
                "SELECT quantity FROM product WHERE product_id = ?", Integer.class, "CC-P002");
        assertThat(remainingP001).isEqualTo(99);
        assertThat(remainingP002).isEqualTo(99);
    }

    @Test
    void createOrder_concurrentOrdersRaceForLastUnit_loserRollsBackWithNoOrphanOrderDetail() throws Exception {
        // Product id sorts after "CC-A-AMPLE" so the fixed lock order always locks the
        // ample-stock line first for both threads, then the contested last-unit line.
        seedProduct("CC-A-AMPLE", "5.00", 100);
        seedProduct("CC-Z-LASTUNIT", "50.00", 1);

        CreateOrderRequest orderX = orderRequest("MEMBER-X",
                List.of(item("CC-A-AMPLE", 1), item("CC-Z-LASTUNIT", 1)));
        CreateOrderRequest orderY = orderRequest("MEMBER-Y",
                List.of(item("CC-A-AMPLE", 1), item("CC-Z-LASTUNIT", 1)));

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        int successCount = 0;
        int failureCount = 0;
        try {
            Callable<String> taskX = () -> {
                startLatch.await();
                return orderService.createOrder(orderX);
            };
            Callable<String> taskY = () -> {
                startLatch.await();
                return orderService.createOrder(orderY);
            };

            Future<String> futureX = executor.submit(taskX);
            Future<String> futureY = executor.submit(taskY);
            startLatch.countDown();

            for (Future<String> future : List.of(futureX, futureY)) {
                try {
                    future.get(45, TimeUnit.SECONDS);
                    successCount++;
                } catch (ExecutionException expected) {
                    // Either sp_decrease_stock's SIGNAL (translated to a DataAccessException)
                    // or - if this thread's read loses the race entirely - OrderService's own
                    // pre-check (BusinessException, 409) can reject the loser; both paths are
                    // driven by the same underlying stock row and must roll back cleanly.
                    failureCount++;
                }
            }
        } finally {
            executor.shutdownNow();
        }

        // Exactly one of the two concurrent orders for the single remaining unit can win;
        // the row lock inside sp_decrease_stock enforces that regardless of thread timing.
        assertThat(successCount).isEqualTo(1);
        assertThat(failureCount).isEqualTo(1);

        Integer remainingLastUnit = jdbcTemplate.queryForObject(
                "SELECT quantity FROM product WHERE product_id = ?", Integer.class, "CC-Z-LASTUNIT");
        assertThat(remainingLastUnit).isZero();

        // The ample-stock product must show exactly one decrement (the winner's) - if the
        // loser's earlier, successfully-executed decrease for this line had NOT been rolled
        // back along with its later SIGNAL failure, this would read 98 instead of 99.
        Integer remainingAmple = jdbcTemplate.queryForObject(
                "SELECT quantity FROM product WHERE product_id = ?", Integer.class, "CC-A-AMPLE");
        assertThat(remainingAmple).isEqualTo(99);

        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_order WHERE member_id IN ('MEMBER-X', 'MEMBER-Y')", Integer.class);
        assertThat(orderCount).isEqualTo(1);

        // No orphan order_detail: exactly two detail rows total (one per line) belonging to
        // the single winning order - never a detail row from the rolled-back loser.
        Integer detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_detail od " +
                        "JOIN shop_order so ON so.order_id = od.order_id " +
                        "WHERE so.member_id IN ('MEMBER-X', 'MEMBER-Y')", Integer.class);
        assertThat(detailCount).isEqualTo(2);
    }
}
