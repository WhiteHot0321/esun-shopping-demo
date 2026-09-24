package com.esun.shop.service;

import com.esun.shop.dto.PaymentRedirect;
import com.esun.shop.model.PaymentResult;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Seam between the shop and a payment provider. Exactly one implementation is active, chosen by
 * {@code payment.provider} ({@code none} | {@code sandbox} | {@code ecpay}). The order/payment state machine in
 * {@link PaymentCallbackService} never sees provider-specific formats: it only receives an authenticated
 * {@link VerifiedPaymentCallback}.
 */
public interface PaymentGateway {
    String providerName();

    /** False means payments cannot be started and callbacks are rejected (no usable provider configured). */
    boolean isEnabled();

    /** True when a buyer may trigger a provider result themselves (sandbox only; a real provider never allows it). */
    boolean supportsSimulation();

    /**
     * Whether "pay again" on an open attempt may reuse its trade number. Providers that reject a re-posted trade
     * number return false: the open attempt is then closed and a fresh one opened (a late success on the old number is
     * still applied, see {@link PaymentCallbackService}).
     */
    default boolean canResumeAttempt() {
        return true;
    }

    /** A new, unique trade number in the format this provider accepts. */
    String newMerchantTradeNo();

    /**
     * What the browser must do to pay: a form to POST to the provider, or empty when no redirect is needed (sandbox).
     * Throws a BusinessException when this provider cannot charge the amount (e.g. non-integer TWD).
     */
    Optional<PaymentRedirect> checkout(String merchantTradeNo, BigDecimal amount, String description);

    /**
     * Authenticates the raw callback parameters and normalises them. Empty for anything not authentic or not
     * well-formed; must never throw. Nothing in the parameters is trusted before this returns a value.
     */
    Optional<VerifiedPaymentCallback> verifyCallback(Map<String, String> parameters);

    /** Sandbox only: the signed parameters a provider would have sent for this outcome. */
    Map<String, String> simulatedCallback(String merchantTradeNo, BigDecimal amount, PaymentResult result,
                                          String providerRef);
}
