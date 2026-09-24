package com.esun.shop.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One payment attempt as shown to its buyer. {@code simulatable} tells the UI it may complete the attempt through
 * the sandbox endpoint instead of redirecting to a provider page.
 */
public record PaymentView(
        long paymentId,
        String orderId,
        String merchantTradeNo,
        BigDecimal amount,
        String status,
        String provider,
        boolean simulatable,
        LocalDateTime createdAt,
        LocalDateTime paidAt) { }
