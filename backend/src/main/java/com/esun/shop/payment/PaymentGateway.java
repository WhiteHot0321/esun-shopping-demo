package com.esun.shop.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/** Provider boundary: domain services do not calculate or accept provider signatures themselves. */
public interface PaymentGateway {
    PaymentForm createPaymentForm(String merchantTradeNo, BigDecimal amount, String orderDescription);

    Optional<VerifiedPaymentCallback> verifyCallback(Map<String, String> parameters);

    String merchantId();
}
