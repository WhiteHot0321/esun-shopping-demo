package com.esun.shop.integration;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.PayStatus;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * High-concurrency (10+ threads) stress coverage for OrderService.createOrder(), submitted in
 * deliberately varied per-item orderings so the 3.3 fixed-lock-order re-sort in
 * OrderService.createOrder() (`lockOrderedItems`) is what actually determines acquisition order,
 * not whatever order each caller happened to submit.
 *
 * Two scenarios, both enabled:
 *
 * 1. {@link #createOrder_manyConcurrentOrdersOnDisjointProducts_allSucceedNoDeadlock()}: each
 *    thread's order only ever touches products no other thread touches, so there is zero
 *    row-level contention between orders. Proves the service handles 12 fully independent
 *    concurrent createOrder() calls correctly (right stock/detail accounting, no crashes, no
 *    cross-talk) - but does NOT exercise cross-order lock contention on a shared product.
 *
 * 2. {@link #createOrder_manyConcurrentOrdersSharingProducts_stockNeverOversoldAndNoDeadlock()}:
 *    many threads competing for a small shared pool of products. While writing this test it
 *    surfaced a real, 100%-reproducible production defect - see that method's Javadoc for the
 *    root cause - which was reported and then fixed in OrderService.createOrder() (decreaseStock
 *    now runs before insertOrderDetail per item, so the FK check's implicit lock can never be
 *    the first lock taken on a row this transaction is about to exclusively lock anyway). This
 *    test is what proves that fix under real contention.
 */
class HighConcurrencyOrderIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seedProduct(String productId, String price, int quantity) {
        CreateProductRequest request = new CreateProductRequest();
        request.setProductId(productId);
        request.setProductName("high concurrency product " + productId);
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

    /**
     * Every failure must be a *recognized* rejection (insufficient stock caught either by
     * OrderService's own pre-check or by sp_decrease_stock's SIGNAL), never an unrecognized
     * exception type that would indicate silent corruption or an unhandled crash path.
     */
    private void assertRecognizedRejection(Throwable failure) {
        assertThat(failure)
                .as("every failure must be a recognized business rejection, not silent/unexpected corruption: %s", failure)
                .isInstanceOfAny(BusinessException.class, DataAccessException.class);
    }

    /**
     * Walks the full cause chain looking for anything deadlock-shaped: Spring's
     * PessimisticLockingFailureException family (CannotAcquireLockException /
     * DeadlockLoserDataAccessException) or a MySQL "Deadlock found" message. Checked by class
     * hierarchy AND message text since MySQL error-code-to-exception mapping has changed across
     * Spring versions and this must not silently stop matching after a library bump.
     */
    private boolean isDeadlockRelated(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.PessimisticLockingFailureException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && message.toLowerCase().contains("deadlock")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void createOrder_manyConcurrentOrdersOnDisjointProducts_allSucceedNoDeadlock() throws Exception {
        final int threadCount = 12;

        // Each thread gets its own private pair of products - no two threads ever touch the same
        // row, so this specifically cannot trigger the FK-lock-upgrade issue documented on the
        // disabled test below. It still genuinely exercises 12 concurrent createOrder() calls
        // sharing the same connection pool / OrderService bean / MySQL server.
        for (int i = 0; i < threadCount; i++) {
            seedProduct("HCD-" + i + "-A", "10.00", 5);
            seedProduct("HCD-" + i + "-B", "20.00", 5);
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                Callable<String> task = () -> {
                    List<OrderItemRequest> items = new ArrayList<>(List.of(
                            item("HCD-" + idx + "-A", 1),
                            item("HCD-" + idx + "-B", 1)));
                    // Alternate submission order per thread - proves lockOrderedItems' own
                    // re-sort works regardless of how the caller assembled the request, even
                    // though there is no cross-thread row to contend over here.
                    if (idx % 2 == 0) {
                        Collections.reverse(items);
                    }
                    startLatch.await();
                    return orderService.createOrder(orderRequest("HCD-MEMBER-" + idx, items));
                };
                futures.add(executor.submit(task));
            }
            startLatch.countDown();

            for (Future<String> future : futures) {
                String orderId = future.get(60, TimeUnit.SECONDS);
                assertThat(orderId).isNotBlank();
            }
        } finally {
            executor.shutdownNow();
        }

        for (int i = 0; i < threadCount; i++) {
            Integer remainingA = jdbcTemplate.queryForObject(
                    "SELECT quantity FROM product WHERE product_id = ?", Integer.class, "HCD-" + i + "-A");
            Integer remainingB = jdbcTemplate.queryForObject(
                    "SELECT quantity FROM product WHERE product_id = ?", Integer.class, "HCD-" + i + "-B");
            assertThat(remainingA).as("product HCD-%d-A stock", i).isEqualTo(4);
            assertThat(remainingB).as("product HCD-%d-B stock", i).isEqualTo(4);
        }

        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_order WHERE member_id LIKE 'HCD-MEMBER-%'", Integer.class);
        assertThat(orderCount).isEqualTo(threadCount);

        Integer detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_detail od " +
                        "JOIN shop_order so ON so.order_id = od.order_id " +
                        "WHERE so.member_id LIKE 'HCD-MEMBER-%'", Integer.class);
        assertThat(detailCount).isEqualTo(threadCount * 2);
    }

    /**
     * Regression coverage for a real production defect found while writing this test (originally
     * landed here @Disabled with the root-cause writeup below; the defect was then reported and
     * fixed in {@code OrderService.createOrder()}, so this test is now enabled and is what proves
     * the fix holds under real contention).
     *
     * <p><b>Setup:</b> 16 threads, 5 shared products (stock 8 each), every thread orders 1 unit
     * of all 5 products with the item list shuffled per-thread (reversed for half, randomly
     * shuffled for the rest) - many threads, multi-item orders, deliberately varied submission
     * order, real lock contention on a small shared pool.
     *
     * <p><b>Expected:</b> since {@code lockOrderedItems} re-sorts every order's items by
     * productId ascending regardless of submission order, exactly 8 of the 16 orders succeed
     * (the 5 products are symmetric, so success is gated by the shared stock=8 bottleneck) and
     * the other 8 fail with a recognized rejection (BusinessException 409, or
     * sp_decrease_stock's SIGNAL translated to a DataAccessException) - never a deadlock.
     *
     * <p><b>Original root cause (before the fix):</b> this reliably threw
     * {@code org.springframework.dao.CannotAcquireLockException: ... Deadlock found when trying
     * to get lock}, the same failure mode as
     * {@code OrderConcurrencyIntegrationTest#createOrder_concurrentOrdersReferencingSameProductsInOppositeOrder_bothCompleteWithoutDeadlock}
     * (which failed 4/4 runs against this environment's real MySQL container before the fix).
     * Confirmed by stack trace and schema inspection:
     *
     * <ul>
     *   <li>{@code order_detail} has {@code FOREIGN KEY (product_id) REFERENCES product(product_id)}
     *       (backend/DB/01_schema.sql).</li>
     *   <li>{@code OrderService.createOrder()}'s per-item loop used to call
     *       {@code orderRepository.insertOrderDetail(detail)} BEFORE
     *       {@code productRepository.decreaseStock(...)} for that same item.</li>
     *   <li>InnoDB's FK constraint check on that INSERT takes an implicit SHARED lock on the
     *       referenced product row; the immediately-following {@code UPDATE product ...} inside
     *       sp_decrease_stock needs to upgrade that to an EXCLUSIVE lock on the very same row.</li>
     *   <li>When two concurrent transactions both reach the same product as their first
     *       shared item, both acquire the shared lock (shared locks don't conflict with each
     *       other) and then both try to upgrade to exclusive at the same time - the textbook
     *       InnoDB "shared-lock-then-upgrade" deadlock, entirely independent of which product a
     *       transaction locks *first* relative to the other. The 3.3 fix only fixes the relative
     *       ORDER in which two DIFFERENT rows are locked across items (preventing the classic
     *       A-locks-P1-wants-P2 / B-locks-P2-wants-P1 cross-cycle); it did nothing for this
     *       same-row upgrade pattern, which could fire even for orders that only share ONE
     *       product.</li>
     * </ul>
     *
     * <p><b>Fix:</b> {@code OrderService.createOrder()}'s per-item loop now calls
     * {@code productRepository.decreaseStock(...)} BEFORE
     * {@code orderRepository.insertOrderDetail(detail)}. The transaction takes the EXCLUSIVE lock
     * first (via the UPDATE), so the later INSERT's FK check only ever needs a shared lock on a
     * row this same transaction already holds exclusively - never a cross-transaction
     * shared-then-exclusive upgrade race. This does not change the cross-item lock ORDER that the
     * 3.3 fix established (still ascending productId), only the order of the two operations
     * within each item.
     */
    @Test
    void createOrder_manyConcurrentOrdersSharingProducts_stockNeverOversoldAndNoDeadlock() throws Exception {
        final int threadCount = 16;
        final int stockPerProduct = 8;
        List<String> productIds = List.of("HCS-P001", "HCS-P002", "HCS-P003", "HCS-P004", "HCS-P005");
        for (String productId : productIds) {
            seedProduct(productId, "10.00", stockPerProduct);
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                Callable<String> task = () -> {
                    List<OrderItemRequest> items = new ArrayList<>();
                    for (String productId : productIds) {
                        items.add(item(productId, 1));
                    }
                    // Deliberately varied submission order per thread: fully reversed for half,
                    // randomly shuffled (fixed seed per thread for reproducibility) for the rest.
                    if (idx % 2 == 0) {
                        Collections.reverse(items);
                    } else {
                        Collections.shuffle(items, new Random(idx));
                    }
                    startLatch.await();
                    return orderService.createOrder(orderRequest("HCS-MEMBER-" + idx, items));
                };
                futures.add(executor.submit(task));
            }
            startLatch.countDown();

            int successCount = 0;
            List<Throwable> failures = new ArrayList<>();
            for (Future<String> future : futures) {
                try {
                    String orderId = future.get(60, TimeUnit.SECONDS);
                    assertThat(orderId).isNotBlank();
                    successCount++;
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }

            for (Throwable failure : failures) {
                assertThat(isDeadlockRelated(failure))
                        .as("failure must not be a deadlock (3.3's fixed lock order must prevent it): %s", failure)
                        .isFalse();
                assertRecognizedRejection(failure);
            }

            // 16 threads competing for 5 symmetric products with stock=8 each: success is gated
            // by full-order atomicity (a partial failure rolls the whole order back), so exactly
            // 8 orders can ever complete regardless of thread interleaving.
            assertThat(successCount).isEqualTo(stockPerProduct);
            assertThat(failures).hasSize(threadCount - stockPerProduct);
        } finally {
            executor.shutdownNow();
        }

        for (String productId : productIds) {
            Integer remaining = jdbcTemplate.queryForObject(
                    "SELECT quantity FROM product WHERE product_id = ?", Integer.class, productId);
            assertThat(remaining).as("product %s stock", productId).isZero();
        }

        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_order WHERE member_id LIKE 'HCS-MEMBER-%'", Integer.class);
        assertThat(orderCount).isEqualTo(stockPerProduct);

        Integer detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_detail od " +
                        "JOIN shop_order so ON so.order_id = od.order_id " +
                        "WHERE so.member_id LIKE 'HCS-MEMBER-%'", Integer.class);
        assertThat(detailCount).isEqualTo(stockPerProduct * productIds.size());
    }
}
