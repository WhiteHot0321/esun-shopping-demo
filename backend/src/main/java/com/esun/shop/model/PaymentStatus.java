package com.esun.shop.model;

/**
 * Lifecycle of one payment attempt, persisted by name in {@code payment.status}.
 *
 * <pre>
 * INITIATED -> SUCCEEDED
 *     |
 *     +------> FAILED
 * SUCCEEDED / FAILED(late success) -> REFUND_REQUIRED
 * </pre>
 * {@code REFUND_REQUIRED} parks money that was taken but cannot be applied to the order (order cancelled, order
 * already paid by another attempt, or the attempt had already been closed). Issuing the refund itself is out of
 * scope for this iteration; the state makes the discrepancy visible instead of silently losing it.
 */
public enum PaymentStatus {
    INITIATED,
    SUCCEEDED,
    FAILED,
    REFUND_REQUIRED
}
