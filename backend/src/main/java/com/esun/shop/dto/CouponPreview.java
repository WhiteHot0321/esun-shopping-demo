package com.esun.shop.dto;

import java.math.BigDecimal;

/** Advisory checkout preview. The authoritative discount is recomputed under lock when the order is created. */
public record CouponPreview(String code, BigDecimal subtotal, BigDecimal discountAmount, BigDecimal total) {
}
