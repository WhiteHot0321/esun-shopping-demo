package com.esun.shop.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Ranking queries for recommendations. Every query is read-only and only counts orders that were not cancelled;
 * candidates are always sellable products (not soft-deleted, in stock). Ordering is fully deterministic
 * (score DESC, product id ASC) so the same data always yields the same list.
 *
 * Signals are aggregated per distinct order, and a signal below {@code minSupport} orders is dropped: a single
 * buyer's basket must not be recoverable from a public "bought together" list.
 */
@Repository
public class RecommendationRepository {
    public record Candidate(String productId, long score) { }

    private static final String SELLABLE = " AND p.deleted_at IS NULL AND p.quantity > 0";
    private static final String LIVE_ORDER = " AND o.order_status <> 'CANCELLED'";

    private final JdbcTemplate jdbcTemplate;

    public RecommendationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Products that appear in the same live orders as any of {@code anchors}, excluding {@code exclude}. */
    public List<Candidate> coPurchased(Collection<String> anchors, Collection<String> exclude,
                                       int minSupport, int limit) {
        if (anchors.isEmpty() || limit <= 0) return List.of();
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT b.product_id, COUNT(DISTINCT b.order_id) AS score "
                + "FROM order_detail a "
                + "JOIN shop_order o ON o.order_id = a.order_id" + LIVE_ORDER
                + " JOIN order_detail b ON b.order_id = a.order_id "
                + "JOIN product p ON p.product_id = b.product_id" + SELLABLE
                + " WHERE a.product_id IN (" + placeholders(anchors.size()) + ")");
        args.addAll(anchors);
        appendNotIn(sql, args, "b.product_id", exclude);
        sql.append(" GROUP BY b.product_id HAVING COUNT(DISTINCT b.order_id) >= ?"
                + " ORDER BY score DESC, b.product_id ASC LIMIT ?");
        args.add(minSupport);
        args.add(limit);
        return jdbcTemplate.query(sql.toString(),
                (rs, i) -> new Candidate(rs.getString(1), rs.getLong(2)), args.toArray());
    }

    /** Best sellers by number of distinct live orders. */
    public List<Candidate> popular(Collection<String> exclude, int minSupport, int limit) {
        if (limit <= 0) return List.of();
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT d.product_id, COUNT(DISTINCT d.order_id) AS score "
                + "FROM order_detail d "
                + "JOIN shop_order o ON o.order_id = d.order_id" + LIVE_ORDER
                + " JOIN product p ON p.product_id = d.product_id" + SELLABLE
                + " WHERE 1 = 1");
        appendNotIn(sql, args, "d.product_id", exclude);
        sql.append(" GROUP BY d.product_id HAVING COUNT(DISTINCT d.order_id) >= ?"
                + " ORDER BY score DESC, d.product_id ASC LIMIT ?");
        args.add(minSupport);
        args.add(limit);
        return jdbcTemplate.query(sql.toString(),
                (rs, i) -> new Candidate(rs.getString(1), rs.getLong(2)), args.toArray());
    }

    /** Newest sellable products; filler so a fresh shop with no order history still shows something. */
    public List<String> newArrivals(Collection<String> exclude, int limit) {
        if (limit <= 0) return List.of();
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT p.product_id FROM product p WHERE p.deleted_at IS NULL AND p.quantity > 0");
        appendNotIn(sql, args, "p.product_id", exclude);
        sql.append(" ORDER BY p.created_at DESC, p.product_id ASC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.queryForList(sql.toString(), String.class, args.toArray());
    }

    /** Distinct products the member has bought in live orders. */
    public List<String> purchasedProductIds(String memberId) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT d.product_id FROM order_detail d JOIN shop_order o ON o.order_id = d.order_id"
                        + " WHERE o.member_id = ?" + LIVE_ORDER + " ORDER BY d.product_id",
                String.class, memberId);
    }

    /** Products currently sitting in the member's cart. */
    public List<String> cartProductIds(String memberEmail) {
        return jdbcTemplate.queryForList(
                "SELECT c.product_id FROM shopping_cart c JOIN member m ON m.id = c.member_id"
                        + " WHERE m.email = ? ORDER BY c.product_id",
                String.class, memberEmail);
    }

    private static void appendNotIn(StringBuilder sql, List<Object> args, String column, Collection<String> exclude) {
        if (exclude.isEmpty()) return;
        sql.append(" AND ").append(column).append(" NOT IN (").append(placeholders(exclude.size())).append(")");
        args.addAll(exclude);
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
