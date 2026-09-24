package com.esun.shop.repository;

import com.esun.shop.model.PayStatus;
import com.esun.shop.model.PaymentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Payment ledger access. Lock order is always shop_order row first, then payment row (checkout, cancel, pay and
 * callback all follow it), so the paths that touch both can never deadlock each other.
 */
@Repository
public class PaymentRepository {
    /** The slice of {@code shop_order} that payment decisions depend on. */
    public record OrderPayState(String orderId, String memberId, String orderStatus, int payStatus, BigDecimal price) { }

    public record Payment(long id, String orderId, String merchantTradeNo, String provider, BigDecimal amount,
                          PaymentStatus status, String failureReason, LocalDateTime createdAt, LocalDateTime paidAt) { }

    private static final String PAYMENT_SELECT = """
            SELECT id, order_id, merchant_trade_no, provider, amount, status, failure_reason, created_at, paid_at
            FROM payment
            """;

    private final JdbcTemplate jdbcTemplate;

    public PaymentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Row-locks the order; all payment decisions are serialised on it. */
    public Optional<OrderPayState> lockOrder(String orderId) {
        return jdbcTemplate.query("SELECT order_id, member_id, order_status, pay_status, price FROM shop_order "
                        + "WHERE order_id = ? FOR UPDATE",
                (rs, n) -> new OrderPayState(rs.getString("order_id"), rs.getString("member_id"),
                        rs.getString("order_status"), rs.getInt("pay_status"), rs.getBigDecimal("price")),
                orderId).stream().findFirst();
    }

    /** Owner of the order without locking it (used to authorise before any state change). */
    public Optional<String> findOrderOwner(String orderId) {
        return jdbcTemplate.query("SELECT member_id FROM shop_order WHERE order_id = ?",
                (rs, n) -> rs.getString(1), orderId).stream().findFirst();
    }

    public Optional<Payment> findOpenAttempt(String orderId) {
        return jdbcTemplate.query(PAYMENT_SELECT + " WHERE order_id = ? AND status = 'INITIATED'",
                (rs, n) -> map(rs), orderId).stream().findFirst();
    }

    public Optional<Payment> findByTradeNo(String merchantTradeNo) {
        return jdbcTemplate.query(PAYMENT_SELECT + " WHERE merchant_trade_no = ?", (rs, n) -> map(rs), merchantTradeNo)
                .stream().findFirst();
    }

    public Optional<Payment> lockById(long id) {
        return jdbcTemplate.query(PAYMENT_SELECT + " WHERE id = ? FOR UPDATE", (rs, n) -> map(rs), id)
                .stream().findFirst();
    }

    public void insert(String orderId, String merchantTradeNo, String provider, BigDecimal amount) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO payment(order_id, merchant_trade_no, provider, amount, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'INITIATED', ?, ?)", orderId, merchantTradeNo, provider, amount, now, now);
    }

    /** Compare-and-set on the attempt's status; returns rows changed (1 = this caller made the transition). */
    public int transition(long id, PaymentStatus from, PaymentStatus to, String failureReason, boolean paid,
                          String providerRef) {
        LocalDateTime now = LocalDateTime.now();
        return jdbcTemplate.update("UPDATE payment SET status = ?, failure_reason = ?, updated_at = ?, "
                        + "provider_ref = COALESCE(?, provider_ref), "
                        + "paid_at = CASE WHEN ? THEN ? ELSE paid_at END WHERE id = ? AND status = ?",
                to.name(), failureReason, now, providerRef, paid, now, id, from.name());
    }

    /** Closes the order's live (INITIATED) attempt, if any; call with the order row locked. */
    public void closeOpenAttempts(String orderId, String reason) {
        jdbcTemplate.update("UPDATE payment SET status = 'FAILED', failure_reason = ?, updated_at = ? "
                + "WHERE order_id = ? AND status = 'INITIATED'", reason, LocalDateTime.now(), orderId);
    }

    /** Compare-and-set PENDING -> PAID; the only statement in the codebase that marks an order paid. */
    public int markOrderPaid(String orderId) {
        return jdbcTemplate.update("UPDATE shop_order SET pay_status = ? WHERE order_id = ? AND pay_status = ?",
                PayStatus.PAID.ordinal(), orderId, PayStatus.PENDING.ordinal());
    }

    /**
     * Called inside the transaction that cancels an order (order row already locked): an open attempt is closed so it
     * can no longer be paid, and money already taken is flagged for refund. A success callback arriving later for the
     * closed attempt is then parked as REFUND_REQUIRED by the callback service.
     */
    public void settleOnCancel(String orderId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("UPDATE payment SET status = 'FAILED', failure_reason = 'ORDER_CANCELLED', updated_at = ? "
                + "WHERE order_id = ? AND status = 'INITIATED'", now, orderId);
        jdbcTemplate.update("UPDATE payment SET status = 'REFUND_REQUIRED', failure_reason = 'ORDER_CANCELLED', "
                + "updated_at = ? WHERE order_id = ? AND status = 'SUCCEEDED'", now, orderId);
    }

    private static Payment map(ResultSet rs) throws SQLException {
        return new Payment(rs.getLong("id"), rs.getString("order_id"), rs.getString("merchant_trade_no"),
                rs.getString("provider"), rs.getBigDecimal("amount"), PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("failure_reason"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toLocalDateTime());
    }
}
