package com.esun.shop.repository;

import com.esun.shop.model.OrderDetail;
import com.esun.shop.model.OrderRequest;
import com.esun.shop.model.ShopOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    public List<ShopOrder> findByMemberId(String memberId, Integer payStatus, int size, int offset) {
        String filter = payStatus == null ? "" : " AND pay_status = ?";
        String sql = "SELECT order_id, member_id, price, pay_status, created_at FROM shop_order "
                + "WHERE member_id = ?" + filter + " ORDER BY created_at DESC, order_id DESC LIMIT ? OFFSET ?";
        Object[] params = payStatus == null ? new Object[]{memberId, size, offset}
                : new Object[]{memberId, payStatus, size, offset};
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapOrder(rs), params);
    }

    public long countByMemberId(String memberId, Integer payStatus) {
        String filter = payStatus == null ? "" : " AND pay_status = ?";
        String sql = "SELECT COUNT(*) FROM shop_order WHERE member_id = ?" + filter;
        return payStatus == null ? jdbcTemplate.queryForObject(sql, Long.class, memberId)
                : jdbcTemplate.queryForObject(sql, Long.class, memberId, payStatus);
    }

    public Optional<ShopOrder> findOrderById(String orderId) {
        return jdbcTemplate.query("SELECT order_id, member_id, price, pay_status, created_at FROM shop_order WHERE order_id = ?",
                (rs, rowNum) -> mapOrder(rs), orderId).stream().findFirst();
    }

    public List<OrderDetail> findDetailsByOrderId(String orderId) {
        return jdbcTemplate.query("SELECT order_item_sn, order_id, product_id, quantity, unit_price, item_price "
                        + "FROM order_detail WHERE order_id = ? ORDER BY order_item_sn", (rs, rowNum) -> {
            OrderDetail detail = new OrderDetail();
            detail.setOrderItemSn(rs.getLong("order_item_sn")); detail.setOrderId(rs.getString("order_id"));
            detail.setProductId(rs.getString("product_id")); detail.setQuantity(rs.getInt("quantity"));
            detail.setUnitPrice(rs.getBigDecimal("unit_price")); detail.setItemPrice(rs.getBigDecimal("item_price"));
            return detail;
        }, orderId);
    }

    private static ShopOrder mapOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        ShopOrder order = new ShopOrder();
        order.setOrderId(rs.getString("order_id")); order.setMemberId(rs.getString("member_id"));
        order.setPrice(rs.getBigDecimal("price")); order.setPayStatus(rs.getInt("pay_status"));
        order.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return order;
    }
}
