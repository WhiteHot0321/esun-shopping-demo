package com.esun.shop.repository;

import com.esun.shop.model.OrderDetail;
import com.esun.shop.model.ShopOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertOrder(ShopOrder order) {
        String sql = "INSERT INTO shop_order(order_id, member_id, price, pay_status) VALUES (?, ?, ?, ?)";
        jdbcTemplate.update(sql, order.getOrderId(), order.getMemberId(), order.getPrice(), order.getPayStatus());
    }

    public void insertOrderDetail(OrderDetail detail) {
        String sql = "INSERT INTO order_detail(order_id, product_id, quantity, stand_price, item_price) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                detail.getOrderId(),
                detail.getProductId(),
                detail.getQuantity(),
                detail.getStandPrice(),
                detail.getItemPrice());
    }
}
