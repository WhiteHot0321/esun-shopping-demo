package com.esun.shop.dto;

import com.esun.shop.model.DiscountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin request to issue a coupon. PERCENT discountValue is a whole percentage (1-99); FIXED is whole dollars.
 * Rule fields are immutable once created; see {@link UpdateCouponRequest} for what can change afterwards.
 */
public record CreateCouponRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$", message = "優惠碼需為 3-32 位英數、底線或連字號")
        String code,
        @NotNull(message = "請選擇折扣類型") DiscountType discountType,
        @NotNull @DecimalMin(value = "1", message = "折扣值至少為 1") @Digits(integer = 10, fraction = 0, message = "折扣值必須為整數")
        BigDecimal discountValue,
        @DecimalMin(value = "1", message = "折扣上限至少為 1") @Digits(integer = 10, fraction = 0, message = "折扣上限必須為整數")
        BigDecimal maxDiscount,
        @DecimalMin(value = "0", message = "最低消費不可為負") @Digits(integer = 10, fraction = 2, message = "最低消費格式不合法")
        BigDecimal minOrderAmount,
        @Positive(message = "總發行量必須大於 0") Integer totalQuota,
        @Positive(message = "每人使用上限必須大於 0") Integer perMemberLimit,
        @NotNull(message = "請輸入開始時間") LocalDateTime startsAt,
        @NotNull(message = "請輸入到期時間") LocalDateTime expiresAt) {
}
