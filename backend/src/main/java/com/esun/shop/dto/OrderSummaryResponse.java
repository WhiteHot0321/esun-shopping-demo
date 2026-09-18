package com.esun.shop.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderSummaryResponse(String orderId, BigDecimal price, Integer payStatus, LocalDateTime createdAt) {
}
