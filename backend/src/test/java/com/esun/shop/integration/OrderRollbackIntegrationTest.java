package com.esun.shop.integration;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.model.PayStatus;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 candidate: a deterministic (single-threaded) proof that a mid-loop stock-deduction
 * failure rolls back the whole {@code @Transactional createOrder()} call - no partial stock
 * deduction, no orphan {@code shop_order}/{@code order_detail} rows.
 *
 * <p>Getting a *real* (not pre-checked) failure deterministically without a second thread is
 * the tricky part: {@code createOrder()}'s pre-check loop validates every line against the one
 * {@code findByIds()} snapshot taken at the top of the method, so a request with a single line
 * that's short on stock never reaches {@code decreaseStock()} at all - there's nothing to roll
 * back. Submitting the *same* product as two separate order lines sidesteps that: each line is
 * checked independently against the same unchanged snapshot (5 in stock, 3 requested - passes
 * twice), but the two lines still hit {@code sp_decrease_stock} one at a time. The first
 * line's decrease actually commits-within-the-transaction (5 -> 2), then the second line's
 * decrease finds only 2 left for a request of 3 and trips the stored procedure's SIGNAL - a
 * genuine post-write failure, which is what makes this test able to catch a missing
 * {@code @Transactional} (removing it would leave stock at 2 and an order_detail row for the
 * first line behind; unlike {@link OrderConcurrencyIntegrationTest}, no second thread needed).
 */
class OrderRollbackIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seedProduct(String productId, String price, int quantity) {
        CreateProductRequest request = new CreateProductRequest();
        request.setProductId(productId);
        request.setProductName("rollback product " + productId);
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

    @Test
    void createOrder_secondLineFailsRealStockCheck_rollsBackFirstLinesDeductionAndAllOrderRows() {
        seedProduct("RB-P001", "10.00", 5);

        CreateOrderRequest request = new CreateOrderRequest();
        request.setMemberId("MEMBER-ROLLBACK");
        request.setPayStatus(PayStatus.PENDING);
        // Two lines for the same product: both pass the pre-check against the initial
        // snapshot (3 <= 5), but only one 3-unit decrease actually fits in 5 units of
        // real stock - the second line's decreaseStock() call is the one that fails.
        request.setItems(List.of(item("RB-P001", 3), item("RB-P001", 3)));

        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(DataAccessException.class);

        Integer remainingStock = jdbcTemplate.queryForObject(
                "SELECT quantity FROM product WHERE product_id = ?", Integer.class, "RB-P001");
        assertThat(remainingStock)
                .as("first line's decreaseStock() must be rolled back along with the second line's failure")
                .isEqualTo(5);

        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_order WHERE member_id = ?", Integer.class, "MEMBER-ROLLBACK");
        assertThat(orderCount).as("no shop_order row may survive the rollback").isZero();

        Integer detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_detail WHERE product_id = ?", Integer.class, "RB-P001");
        assertThat(detailCount).as("no order_detail row may survive the rollback").isZero();
    }
}
