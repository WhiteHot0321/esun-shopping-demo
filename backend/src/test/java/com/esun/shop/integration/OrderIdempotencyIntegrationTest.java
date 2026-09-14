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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderIdempotencyIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final int CALLERS = 20;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seedProduct(String productId, int quantity) {
        CreateProductRequest product = new CreateProductRequest();
        product.setProductId(productId);
        product.setProductName("idempotency product " + productId);
        product.setPrice(new BigDecimal("10.00"));
        product.setQuantity(quantity);
        productService.createProduct(product);
    }

    private CreateOrderRequest request(String requestId, String memberId, List<OrderItemRequest> items) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(requestId);
        request.setMemberId(memberId);
        request.setPayStatus(PayStatus.PENDING);
        request.setItems(items);
        return request;
    }

    private OrderItemRequest item(String productId, int quantity) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    @Test
    void createOrder_twentySameRequestIds_createsOneOrderAndReturnsOneId() throws Exception {
        String productId = "IDEM-SAME";
        String requestId = UUID.randomUUID().toString();
        seedProduct(productId, 5);

        List<String> orderIds = runConcurrently(CALLERS,
                () -> orderService.createOrder(request(requestId, "IDEM-MEMBER", List.of(item(productId, 1)))));

        assertThat(orderIds).hasSize(CALLERS).containsOnly(orderIds.get(0));
        assertThat(count("SELECT COUNT(*) FROM order_request WHERE request_id = ?", requestId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM shop_order WHERE order_id = ?", orderIds.get(0))).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM order_detail WHERE order_id = ?", orderIds.get(0))).isEqualTo(1);
        assertThat(quantity(productId)).isEqualTo(4);
    }

    @Test
    void createOrder_twentyDistinctRequestIds_createsTwentyOrders() throws Exception {
        String productId = "IDEM-DISTINCT";
        seedProduct(productId, CALLERS);

        List<String> orderIds = runConcurrently(CALLERS, () -> orderService.createOrder(request(
                UUID.randomUUID().toString(), "IDEM-DISTINCT", List.of(item(productId, 1)))));

        assertThat(orderIds).hasSize(CALLERS).doesNotHaveDuplicates();
        assertThat(quantity(productId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM shop_order WHERE member_id = ?", "IDEM-DISTINCT")).isEqualTo(CALLERS);
    }

    @Test
    void createOrder_postWriteFailureRollsBackClaimAndAllowsSameKeyRetry() {
        String productId = "IDEM-ROLLBACK";
        String requestId = UUID.randomUUID().toString();
        seedProduct(productId, 3);

        assertThatThrownBy(() -> orderService.createOrder(request(requestId, "IDEM-ROLLBACK",
                List.of(item(productId, 2), item(productId, 2)))))
                .isInstanceOf(DataAccessException.class)
                .satisfies(error -> {
                    SQLException sqlException = findSqlException(error);
                    assertThat((Object) sqlException).isNotNull();
                    assertThat(sqlException.getSQLState()).isEqualTo("45000");
                    // sp_decrease_stock SIGNALs "庫存不足或商品不存在". The container's
                    // SQL script import currently corrupts non-ASCII MESSAGE_TEXT, so assert
                    // MySQL's user-SIGNAL vendor code plus a returned message instead of a
                    // locale/encoding-dependent literal.
                    assertThat(sqlException.getErrorCode()).isEqualTo(1644);
                    assertThat(sqlException.getMessage()).isNotBlank();
                });

        assertThat(quantity(productId)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM order_request WHERE request_id = ?", requestId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM shop_order WHERE member_id = ?", "IDEM-ROLLBACK")).isZero();
        assertThat(count("SELECT COUNT(*) FROM order_detail od JOIN shop_order so ON so.order_id = od.order_id WHERE so.member_id = ?", "IDEM-ROLLBACK")).isZero();

        String orderId = orderService.createOrder(request(requestId, "IDEM-ROLLBACK", List.of(item(productId, 3))));
        assertThat(orderId).isNotBlank();
        assertThat(quantity(productId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM order_request WHERE request_id = ?", requestId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM shop_order WHERE order_id = ?", orderId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM order_detail WHERE order_id = ?", orderId)).isEqualTo(1);
    }

    @Test
    void createOrder_sameRequestIdFromAnotherMember_isRejectedWithoutCreatingAnotherOrder() {
        String productId = "IDEM-MISMATCH";
        String requestId = UUID.randomUUID().toString();
        seedProduct(productId, 2);
        String originalOrderId = orderService.createOrder(request(requestId, "IDEM-OWNER", List.of(item(productId, 1))));

        assertThatThrownBy(() -> orderService.createOrder(request(requestId, "IDEM-OTHER", List.of(item(productId, 1)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getStatus().value()).isEqualTo(409);
                    assertThat(business.getMessage()).doesNotContain(originalOrderId);
                });

        assertThat(count("SELECT COUNT(*) FROM order_request WHERE request_id = ?", requestId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM shop_order WHERE order_id = ?", originalOrderId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM shop_order WHERE member_id = ?", "IDEM-OTHER")).isZero();
        assertThat(quantity(productId)).isEqualTo(1);
    }

    private List<String> runConcurrently(int callers, ThrowingOrderCall call) throws Exception {
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(20, TimeUnit.SECONDS);
                    return call.execute();
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> values = new ArrayList<>();
            for (Future<String> future : futures) {
                values.add(future.get(30, TimeUnit.SECONDS));
            }
            return values;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private int quantity(String productId) {
        return jdbcTemplate.queryForObject("SELECT quantity FROM product WHERE product_id = ?", Integer.class, productId);
    }

    private int count(String sql, String parameter) {
        return jdbcTemplate.queryForObject(sql, Integer.class, parameter);
    }

    private SQLException findSqlException(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface ThrowingOrderCall {
        String execute() throws Exception;
    }
}
