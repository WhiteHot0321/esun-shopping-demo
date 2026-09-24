package com.esun.shop.service;

import com.esun.shop.model.PaymentResult;

import java.math.BigDecimal;

/**
 * Seam between the shop and a payment provider. The shop only relies on three things from a provider: whether it
 * is switched on, and that a callback can be authenticated (signature over the trade number, amount and result).
 * Swapping in a real provider (e.g. ECPay's CheckMacValue) means implementing this interface; the order/payment
 * state machine in {@code PaymentCallbackService} does not change.
 */
public interface PaymentGateway {
    String providerName();

    /** False means payments cannot be started and callbacks are rejected (no provider configured). */
    boolean isEnabled();

    /** True when a buyer may trigger a provider result themselves (sandbox only; a real provider never allows it). */
    boolean supportsSimulation();

    String sign(String merchantTradeNo, BigDecimal amount, PaymentResult result, String providerRef);

    /** Constant-time verification; must return false (never throw) for any malformed input. */
    boolean verify(String merchantTradeNo, BigDecimal amount, PaymentResult result, String providerRef,
                   String signature);
}
