package com.esun.shop.service;

import com.esun.shop.dto.PaymentRedirect;
import com.esun.shop.model.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sandbox provider ({@code payment.provider=sandbox}): callbacks are authenticated with HMAC-SHA256 over a canonical
 * string, the same shape as real providers' signed callbacks. In sandbox mode the buyer can simulate the provider's
 * answer for their own order - fine for development and demos, never for production.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "sandbox")
public class HmacPaymentGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(HmacPaymentGateway.class);
    private static final String HMAC = "HmacSHA256";

    private final byte[] secret;

    public HmacPaymentGateway(@Value("${payment.callback-secret}") String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("payment.callback-secret must be at least 16 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (secret.startsWith("dev-only")) {
            log.error("Payment SANDBOX is enabled with the well-known dev callback secret; set PAYMENT_CALLBACK_SECRET.");
        }
        log.warn("Payment SANDBOX is enabled: buyers can mark their own orders paid. Never enable in production.");
    }

    @Override
    public String providerName() {
        return "sandbox";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean supportsSimulation() {
        return true;
    }

    @Override
    public String newMerchantTradeNo() {
        return "PAY" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    @Override
    public Optional<PaymentRedirect> checkout(String merchantTradeNo, BigDecimal amount, String description) {
        return Optional.empty();
    }

    @Override
    public Optional<VerifiedPaymentCallback> verifyCallback(Map<String, String> parameters) {
        try {
            String tradeNo = parameters.get("merchantTradeNo");
            BigDecimal amount = new BigDecimal(parameters.get("amount"));
            PaymentResult result = PaymentResult.valueOf(parameters.get("result"));
            String providerRef = parameters.get("providerRef");
            if (!verify(tradeNo, amount, result, providerRef, parameters.get("signature"))) return Optional.empty();
            return Optional.of(new VerifiedPaymentCallback(tradeNo, amount, result, providerRef));
        } catch (RuntimeException ex) {
            // missing/non-numeric/unknown-enum fields: not something we ever signed
            return Optional.empty();
        }
    }

    @Override
    public Map<String, String> simulatedCallback(String merchantTradeNo, BigDecimal amount, PaymentResult result,
                                                 String providerRef) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("merchantTradeNo", merchantTradeNo);
        parameters.put("amount", amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        parameters.put("result", result.name());
        if (providerRef != null) parameters.put("providerRef", providerRef);
        parameters.put("signature", sign(merchantTradeNo, amount, result, providerRef));
        return parameters;
    }

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
                    signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
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
