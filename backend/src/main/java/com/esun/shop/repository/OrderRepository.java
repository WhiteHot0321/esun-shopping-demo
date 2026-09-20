package com.esun.shop.repository;

import com.esun.shop.model.OrderDetail;
import com.esun.shop.model.OrderRequest;
import com.esun.shop.model.ShopOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertOrder(ShopOrder order, Long shippingAddressId) {
        String sql = "INSERT INTO shop_order(order_id, member_id, shipping_address_id, price, pay_status) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, order.getOrderId(), order.getMemberId(), shippingAddressId,
                order.getPrice(), order.getPayStatus());
    }

    public void claimRequest(String requestId, String orderId, String memberId) {
        String sql = "INSERT INTO order_request(request_id, order_id, member_id) VALUES (?, ?, ?)";
        jdbcTemplate.update(sql, requestId, orderId, memberId);
    }

    public Optional<OrderRequest> findRequestById(String requestId) {
        String sql = "SELECT request_id, order_id, member_id FROM order_request WHERE request_id = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            OrderRequest request = new OrderRequest();
            request.setRequestId(rs.getString("request_id"));
            request.setOrderId(rs.getString("order_id"));
            request.setMemberId(rs.getString("member_id"));
            return request;
        }, requestId).stream().findFirst();
    }

    public void insertOrderDetail(OrderDetail detail) {
        String sql = "INSERT INTO order_detail(order_id, product_id, quantity, unit_price, item_price) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                detail.getOrderId(),
                detail.getProductId(),
                detail.getQuantity(),
                detail.getUnitPrice(),
                detail.getItemPrice());
    }
}
