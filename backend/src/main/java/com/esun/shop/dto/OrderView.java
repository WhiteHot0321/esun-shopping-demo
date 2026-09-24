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
        List<String> allowedActions) {

    public record Item(String productId, String productName, int quantity, BigDecimal unitPrice, BigDecimal itemPrice) { }

    public record TimelineEntry(String fromStatus, String toStatus, String actorRole, LocalDateTime createdAt) { }
}
