package com.esun.shop.integration;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.PayStatus;
import com.esun.shop.model.Product;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests running OrderService/ProductService against a real MySQL
 * container (see {@link AbstractMySqlIntegrationTest}), so the actual stored
 * procedures (sp_add_product, sp_get_available_products, sp_decrease_stock)
 * are exercised instead of mocked repositories.
 */
class OrderServiceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seedProduct(String productId, String price, int quantity) {
        CreateProductRequest request = new CreateProductRequest();
        request.setProductId(productId);
        request.setProductName("integration product " + productId);
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

    private CreateOrderRequest orderRequest(List<OrderItemRequest> items) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setMemberId("IT-MEMBER");
        request.setPayStatus(PayStatus.PENDING);
        request.setItems(items);
        return request;
    }

    @Test
    void createOrder_realStoredProcedures_persistsOrderAndDecreasesStockViaSpDecreaseStock() {
        seedProduct("IT-P001", "100.00", 10);

        String orderId = orderService.createOrder(orderRequest(List.of(item("IT-P001", 3))));

        Integer remainingStock = jdbcTemplate.queryForObject(
                "SELECT quantity FROM product WHERE product_id = ?", Integer.class, "IT-P001");
        assertThat(remainingStock).isEqualTo(7);

        BigDecimal persistedPrice = jdbcTemplate.queryForObject(
                "SELECT price FROM shop_order WHERE order_id = ?", BigDecimal.class, orderId);
        assertThat(persistedPrice).isEqualByComparingTo("300.00");

        Integer detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_detail WHERE order_id = ?", Integer.class, orderId);
        assertThat(detailCount).isEqualTo(1);
    }

    @Test
    void createOrder_stockExactlyDepleted_spDecreaseStockAllowsBuyingLastUnit() {
        seedProduct("IT-P002", "20.00", 1);

        orderService.createOrder(orderRequest(List.of(item("IT-P002", 1))));

        Integer remainingStock = jdbcTemplate.queryForObject(
                "SELECT quantity FROM product WHERE product_id = ?", Integer.class, "IT-P002");
        assertThat(remainingStock).isZero();
    }

    @Test
    void createOrder_stockBelowRequestedQuantity_spDecreaseStockSignalsAndOrderIsRejected() {
        seedProduct("IT-P003", "20.00", 1);

        assertThatThrownBy(() -> orderService.createOrder(orderRequest(List.of(item("IT-P003", 2)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        Integer remainingStock = jdbcTemplate.queryForObject(
                "SELECT quantity FROM product WHERE product_id = ?", Integer.class, "IT-P003");
        assertThat(remainingStock).isEqualTo(1);
    }

    @Test
    void getAvailableProducts_realSpGetAvailableProducts_excludesOutOfStockAndOrdersByProductId() {
        seedProduct("IT-P901-AVAILABLE", "5.00", 3);
        seedProduct("IT-P902-SOLDOUT", "5.00", 0);

        List<Product> available = productService.getAvailableProducts();

        assertThat(available)
                .extracting(Product::getProductId)
                .contains("IT-P901-AVAILABLE")
                .doesNotContain("IT-P902-SOLDOUT");
    }
}
