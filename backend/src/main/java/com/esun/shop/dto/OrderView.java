package com.esun.shop.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Read model of one order: header, receiver snapshot, line items and status timeline. */
public record OrderView(
        String orderId,
        String memberId,
        String status,
        BigDecimal price,
        LocalDateTime createdAt,
        String receiverName,
        String receiverPhone,
        String shippingAddress,
        List<Item> items,
        List<TimelineEntry> timeline,
        /** Statuses the caller may move this order to right now; empty for read-only views. */
        List<String> allowedActions,
        /** PENDING or PAID; only ever advanced by a verified payment callback. */
        String payStatus,
        /** Status of the newest payment attempt (INITIATED/SUCCEEDED/FAILED/REFUND_REQUIRED), null if none yet. */
        String paymentStatus,
        /** Buyer view only: the buyer may (re)start payment for this order right now. */
        boolean payable,
        /** Coupon code applied at checkout, or null. Hidden from a seller who sees only part of the order. */
        String couponCode,
        /** Discount already deducted from {@code price} (price + discountAmount = pre-discount subtotal). */
        BigDecimal discountAmount) {

    public record Item(String productId, String productName, int quantity, BigDecimal unitPrice, BigDecimal itemPrice) { }

    public record TimelineEntry(String fromStatus, String toStatus, String actorRole, LocalDateTime createdAt) { }
}
