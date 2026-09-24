package com.esun.shop.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Admin read model of a coupon. status is derived: DISABLED, SCHEDULED, EXPIRED, EXHAUSTED or ACTIVE. */
public record CouponView(
        long id,
        String code,
        String discountType,
        BigDecimal discountValue,
        BigDecimal maxDiscount,
        BigDecimal minOrderAmount,
        Integer totalQuota,
        int usedCount,
        int perMemberLimit,
        LocalDateTime startsAt,
        LocalDateTime expiresAt,
        boolean active,
        String status,
        String createdBy,
        LocalDateTime createdAt) {
}
