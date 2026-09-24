package com.esun.shop.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

/** Full replacement of the only mutable coupon fields. A null totalQuota means unlimited. */
public record UpdateCouponRequest(
        @NotNull(message = "請指定啟用狀態") Boolean active,
        @NotNull(message = "請輸入到期時間") LocalDateTime expiresAt,
        @Positive(message = "總發行量必須大於 0") Integer totalQuota) {
}
