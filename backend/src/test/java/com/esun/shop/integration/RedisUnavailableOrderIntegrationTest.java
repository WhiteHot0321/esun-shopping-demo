package com.esun.shop.integration;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.model.PayStatus;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.StockCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@TestPropertySource(properties = {"stock.redis.enabled=true", "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1", "spring.data.redis.connect-timeout=200ms",
        "spring.data.redis.timeout=200ms", "stock.redis.audit-interval-ms=3600000"})
class RedisUnavailableOrderIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired OrderService orders;
    @Autowired JdbcTemplate db;
    @SpyBean StockCacheService cache;

    @Test
    void realConnectionFailureFallsBackWithoutMintingCompensationAndPreservesRollback() {
        String product = "OUT" + UUID.randomUUID().toString().substring(0, 12);
        db.update("INSERT INTO product(product_id,product_name,price,quantity) VALUES (?,?,10,3)", product, product);
        CreateOrderRequest request = request(product, 2);
        // The DB stored procedure rejects the second repeated line after the first
        // decrement. No Redis reservation was confirmed, so no compensation is due.
        request.setItems(List.of(request.getItems().get(0), request.getItems().get(0)));
        assertThatThrownBy(() -> orders.createOrder(request)).isInstanceOf(DataAccessException.class);
        assertThat(db.queryForObject("SELECT quantity FROM product WHERE product_id=?", Integer.class, product)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_request WHERE request_id=?", Integer.class, request.getRequestId())).isZero();
        request.setItems(List.of(request.getItems().get(0)));
        String id = orders.createOrder(request);
        assertThat(orders.createOrder(request)).isEqualTo(id);
        assertThat(db.queryForObject("SELECT quantity FROM product WHERE product_id=?", Integer.class, product)).isEqualTo(1);
        verify(cache, never()).compensate(anyList());
    }

    private CreateOrderRequest request(String product, int quantity) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(product);
        item.setQuantity(quantity);
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setMemberId("OUTAGE-TEST");
        request.setPayStatus(PayStatus.PENDING);
        request.setItems(List.of(item));
        return request;
    }
}
