package com.esun.shop.payment;

import java.math.BigDecimal;

/** Fields trusted only after the provider gateway has verified its callback signature. */
public record VerifiedPaymentCallback(
        String merchantId,
        String merchantTradeNo,
        BigDecimal amount,
        String providerTransactionId,
        boolean successful) {
}
