package com.esun.shop.service;

import com.esun.shop.model.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Sandbox provider: callbacks are authenticated with HMAC-SHA256 over a canonical string, the same shape as real
 * providers' signed callbacks. Disabled by default ({@code payment.sandbox.enabled=false}) because in sandbox mode
 * the buyer can simulate the provider's answer for their own order - fine for development and demos, never for
 * production.
 */
@Component
public class HmacPaymentGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(HmacPaymentGateway.class);
    private static final String HMAC = "HmacSHA256";

    private final byte[] secret;
    private final boolean enabled;

    public HmacPaymentGateway(@Value("${payment.callback-secret}") String secret,
                              @Value("${payment.sandbox.enabled:false}") boolean enabled) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("payment.callback-secret must be at least 16 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.enabled = enabled;
        if (enabled && secret.startsWith("dev-only")) {
            log.error("Payment SANDBOX is enabled with the well-known dev callback secret; set PAYMENT_CALLBACK_SECRET.");
        }
        if (enabled) {
            log.warn("Payment SANDBOX is enabled: buyers can mark their own orders paid. Never enable in production.");
        }
    }

    @Override
    public String providerName() {
        return "sandbox";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean supportsSimulation() {
        return enabled;
    }

    @Override
    public String sign(String merchantTradeNo, BigDecimal amount, PaymentResult result, String providerRef) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            byte[] digest = mac.doFinal(canonical(merchantTradeNo, amount, result, providerRef)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    @Override
    public boolean verify(String merchantTradeNo, BigDecimal amount, PaymentResult result, String providerRef,
                          String signature) {
        if (merchantTradeNo == null || amount == null || result == null || signature == null) return false;
        // Bound the untrusted number before any arithmetic on it (e.g. 1E+100000000 must not reach setScale).
        if (amount.signum() <= 0 || amount.scale() < 0 || amount.scale() > 2 || amount.precision() > 14) return false;
        // '|' is the canonical-string separator: refusing it keeps two different field tuples from sharing a payload.
        if (merchantTradeNo.indexOf('|') >= 0 || (providerRef != null && providerRef.indexOf('|') >= 0)) return false;
        try {
            String expected = sign(merchantTradeNo, amount, result, providerRef);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (ArithmeticException ex) {
            // Amount with more than two decimals cannot be canonicalised: not something we ever signed.
            return false;
        }
    }

    /** Fields are fixed-order and '|'-joined; the amount is normalised to two decimals so "100" and "100.00" agree. */
    private static String canonical(String merchantTradeNo, BigDecimal amount, PaymentResult result, String providerRef) {
        return merchantTradeNo + "|" + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString() + "|"
                + result.name() + "|" + (providerRef == null ? "" : providerRef);
    }
}
