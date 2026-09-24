package com.esun.shop.repository;

import com.esun.shop.dto.OrderView;
import com.esun.shop.model.OrderDetail;
import com.esun.shop.model.OrderRequest;
import com.esun.shop.model.ShopOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class OrderRepository {
    /** Order header joined with its (optional) shipping-address snapshot. */
    public record OrderHeader(String orderId, String memberId, String status, BigDecimal price,
                              LocalDateTime createdAt, String receiverName, String receiverPhone,
                              String shippingAddress) { }

    /** One order line plus the owning seller, so callers can apply seller scoping in memory. */
    public record ItemRow(String orderId, String productId, String productName, int quantity,
                          BigDecimal unitPrice, BigDecimal itemPrice, String creatorId) { }

    private static final String HEADER_SELECT = """
            SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at,
                   a.receiver_name, a.phone, CONCAT_WS(' ', a.postal_code, a.address) AS full_address
            FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id
            """;
    /** Seller scope: the order contains at least one product created by the seller. */
    private static final String SELLER_SCOPE = """
            EXISTS (SELECT 1 FROM order_detail d JOIN product p ON p.product_id = d.product_id
                    WHERE d.order_id = o.order_id AND p.creator_id = ?)
            """;

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

    public void insertStatusHistory(String orderId, String fromStatus, String toStatus, String actor, String actorRole) {
        jdbcTemplate.update("INSERT INTO order_status_history(order_id, from_status, to_status, actor, actor_role) "
                + "VALUES (?, ?, ?, ?, ?)", orderId, fromStatus, toStatus, actor, actorRole);
    }

    /** Row-locks the order so concurrent transitions/cancellations serialize on it. */
    public Optional<OrderHeader> lockHeader(String orderId) {
        return jdbcTemplate.query(HEADER_SELECT + " WHERE o.order_id = ? FOR UPDATE", (rs, n) -> mapHeader(rs), orderId)
                .stream().findFirst();
    }

    public Optional<OrderHeader> findHeader(String orderId) {
        return jdbcTemplate.query(HEADER_SELECT + " WHERE o.order_id = ?", (rs, n) -> mapHeader(rs), orderId)
                .stream().findFirst();
    }

    /** Compare-and-set: succeeds only if the order is still in {@code fromStatus}. */
    public int updateStatus(String orderId, String fromStatus, String toStatus) {
        return jdbcTemplate.update("UPDATE shop_order SET order_status = ? WHERE order_id = ? AND order_status = ?",
                toStatus, orderId, fromStatus);
    }

    public List<OrderHeader> findHeadersByMember(String memberId, String status, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String sql = HEADER_SELECT + memberWhere(memberId, status, args)
                + " ORDER BY o.created_at DESC, o.order_id DESC LIMIT ? OFFSET ?";
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql, (rs, n) -> mapHeader(rs), args.toArray());
    }

    public long countByMember(String memberId, String status) {
        List<Object> args = new ArrayList<>();
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shop_order o" + memberWhere(memberId, status, args),
                Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /** {@code sellerId == null} means unrestricted (admin). */
    public List<OrderHeader> findHeadersForSeller(String sellerId, String status, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String sql = HEADER_SELECT + sellerWhere(sellerId, status, args)
                + " ORDER BY o.created_at DESC, o.order_id DESC LIMIT ? OFFSET ?";
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql, (rs, n) -> mapHeader(rs), args.toArray());
    }

    public long countForSeller(String sellerId, String status) {
        List<Object> args = new ArrayList<>();
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shop_order o" + sellerWhere(sellerId, status, args),
                Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private static String memberWhere(String memberId, String status, List<Object> args) {
        args.add(memberId);
        String where = " WHERE o.member_id = ?";
        if (status != null) {
            where += " AND o.order_status = ?";
            args.add(status);
        }
        return where;
    }

    private static String sellerWhere(String sellerId, String status, List<Object> args) {
        List<String> clauses = new ArrayList<>();
        if (sellerId != null) {
            clauses.add(SELLER_SCOPE);
            args.add(sellerId);
        }
        if (status != null) {
            clauses.add("o.order_status = ?");
            args.add(status);
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    /** Batch-loads lines for a page of orders (one query, not one per order). */
    public Map<String, List<ItemRow>> findItems(Collection<String> orderIds) {
        Map<String, List<ItemRow>> result = new HashMap<>();
        if (orderIds.isEmpty()) return result;
        jdbcTemplate.query("SELECT d.order_id, d.product_id, p.product_name, d.quantity, d.unit_price, d.item_price, p.creator_id "
                + "FROM order_detail d JOIN product p ON p.product_id = d.product_id "
                + "WHERE d.order_id IN (" + placeholders(orderIds) + ") ORDER BY d.order_id, d.product_id",
                rs -> {
                    ItemRow row = new ItemRow(rs.getString("order_id"), rs.getString("product_id"),
                            rs.getString("product_name"), rs.getInt("quantity"), rs.getBigDecimal("unit_price"),
                            rs.getBigDecimal("item_price"), rs.getString("creator_id"));
                    result.computeIfAbsent(row.orderId(), k -> new ArrayList<>()).add(row);
                }, orderIds.toArray());
        return result;
    }

    public Map<String, List<OrderView.TimelineEntry>> findHistory(Collection<String> orderIds) {
        Map<String, List<OrderView.TimelineEntry>> result = new HashMap<>();
        if (orderIds.isEmpty()) return result;
        jdbcTemplate.query("SELECT order_id, from_status, to_status, actor_role, created_at FROM order_status_history "
                + "WHERE order_id IN (" + placeholders(orderIds) + ") ORDER BY order_id, id",
                rs -> {
                    result.computeIfAbsent(rs.getString("order_id"), k -> new ArrayList<>())
                            .add(new OrderView.TimelineEntry(rs.getString("from_status"), rs.getString("to_status"),
                                    rs.getString("actor_role"), rs.getTimestamp("created_at").toLocalDateTime()));
                }, orderIds.toArray());
        return result;
    }

    /** Returns purchased quantity to stock; soft-deleted products are restored too. Callers pass ids in sorted order. */
    public void restoreStock(String productId, int quantity) {
        jdbcTemplate.update("UPDATE product SET quantity = quantity + ? WHERE product_id = ?", quantity, productId);
    }

    private static String placeholders(Collection<String> values) {
        return String.join(",", Collections.nCopies(values.size(), "?"));
    }

    private static OrderHeader mapHeader(ResultSet rs) throws SQLException {
        return new OrderHeader(rs.getString("order_id"), rs.getString("member_id"), rs.getString("order_status"),
                rs.getBigDecimal("price"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("receiver_name"), rs.getString("phone"), rs.getString("full_address"));
    }
}
