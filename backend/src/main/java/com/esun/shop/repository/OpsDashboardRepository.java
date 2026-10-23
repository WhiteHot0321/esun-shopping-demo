package com.esun.shop.repository;

import com.esun.shop.model.PayStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Read-only aggregates for the admin dashboard. Window bounds are stored-zone wall-clock (see DashboardWindow). */
@Repository
public class OpsDashboardRepository {
    public record OrderTotals(long orderCount, BigDecimal revenue) { }

    public record LowStockProduct(String productId, String productName, int quantity) { }

    /**
     * Attempts the system closes itself (order cancelled, user retried, another attempt succeeded) are not payment
     * failures. These literals are written by PaymentRepository.settleOnCancel and PaymentService/PaymentCallbackService
     * via closeOpenAttempts; keep them in step.
     */
    static final List<String> SYSTEM_CLOSURE_REASONS =
            List.of("ORDER_CANCELLED", "SUPERSEDED_BY_RETRY", "SUPERSEDED_BY_LATE_SUCCESS");

    private final JdbcTemplate jdbcTemplate;

    public OpsDashboardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Orders created in {@code [from, to)} that are not cancelled; revenue counts only those already paid. */
    public OrderTotals ordersCreated(LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*), COALESCE(SUM(CASE WHEN pay_status = ? THEN price ELSE 0 END), 0)
                FROM shop_order
                WHERE created_at >= ? AND created_at < ? AND order_status <> 'CANCELLED'
                """,
                (rs, n) -> new OrderTotals(rs.getLong(1), rs.getBigDecimal(2).setScale(2)),
                PayStatus.PAID.ordinal(), from, to);
    }

    /** Backlog, not limited to today: live orders still waiting for payment. */
    public long pendingPayment() {
        return count("SELECT COUNT(*) FROM shop_order WHERE pay_status = ? AND order_status <> 'CANCELLED'",
                PayStatus.PENDING.ordinal());
    }

    /** Backlog: money taken for an order that cannot be fulfilled; the refund itself is manual. */
    public long refundRequired() {
        return count("SELECT COUNT(*) FROM payment WHERE status = 'REFUND_REQUIRED'");
    }

    /** Attempts that failed in {@code [from, to)}, excluding the system closures in {@link #SYSTEM_CLOSURE_REASONS}. */
    public long paymentFailures(LocalDateTime from, LocalDateTime to) {
        String placeholders = String.join(", ", java.util.Collections.nCopies(SYSTEM_CLOSURE_REASONS.size(), "?"));
        Object[] args = new Object[2 + SYSTEM_CLOSURE_REASONS.size()];
        args[0] = from;
        args[1] = to;
        for (int i = 0; i < SYSTEM_CLOSURE_REASONS.size(); i++) args[2 + i] = SYSTEM_CLOSURE_REASONS.get(i);
        // failure_reason may be NULL, and NULL NOT IN (...) is NULL, so it has to be allowed explicitly.
        return count("SELECT COUNT(*) FROM payment WHERE status = 'FAILED' AND updated_at >= ? AND updated_at < ? "
                + "AND (failure_reason IS NULL OR failure_reason NOT IN (" + placeholders + "))", args);
    }

    /** Live (not soft-deleted) products at or below the threshold. Database quantity is the source of truth. */
    public long lowStockCount(int threshold) {
        return count("SELECT COUNT(*) FROM product WHERE deleted_at IS NULL AND quantity <= ?", threshold);
    }

    public List<LowStockProduct> lowStock(int threshold, int limit) {
        return jdbcTemplate.query("""
                SELECT product_id, product_name, quantity FROM product
                WHERE deleted_at IS NULL AND quantity <= ?
                ORDER BY quantity ASC, product_id ASC LIMIT ?
                """,
                (rs, n) -> new LowStockProduct(rs.getString("product_id"), rs.getString("product_name"),
                        rs.getInt("quantity")),
                threshold, limit);
    }

    private long count(String sql, Object... args) {
        Long total = jdbcTemplate.queryForObject(sql, Long.class, args);
        return total == null ? 0 : total;
    }
}
