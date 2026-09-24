package com.esun.shop.service;

import com.esun.shop.config.EcpayProperties;
import com.esun.shop.dto.PaymentRedirect;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.PaymentResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * ECPay (綠界) AioCheckOut with SHA-256 CheckMacValue ({@code payment.provider=ecpay}). The signing routine and its
 * published test vector come from the earlier {@code codex/phase31-pay-status} work; this adapter plugs it into the
 * shared payment state machine instead of carrying its own.
 *
 * Only credit-card payment is offered ({@code ChoosePayment=Credit}). ATM and convenience-store payments first send
 * a callback with RtnCode != 1 that means "payment code issued", not "failed"; treating that as a decline would close
 * the attempt and then turn the buyer's real payment into a refund. Supporting them needs a "pending" callback result.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "ecpay")
public class EcpayPaymentGateway implements PaymentGateway {
    private static final DateTimeFormatter TRADE_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("\\d{1,10}");

    private final EcpayProperties properties;

    public EcpayPaymentGateway(EcpayProperties properties) {
        if (!properties.isComplete()) {
            throw new IllegalStateException("payment.provider=ecpay requires ecpay.merchant-id, hash-key, hash-iv, "
                    + "payment-url, callback-url and return-url");
        }
        this.properties = properties;
    }

    @Override
    public String providerName() {
        return "ecpay";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /** ECPay does not accept the same MerchantTradeNo re-posted with a new MerchantTradeDate. */
    @Override
    public boolean canResumeAttempt() {
        return false;
    }

    @Override
    public boolean supportsSimulation() {
        return false;
    }

    /** ECPay allows at most 20 alphanumeric characters and requires uniqueness per attempt. */
    @Override
    public String newMerchantTradeNo() {
        return "E" + UUID.randomUUID().toString().replace("-", "").substring(0, 19).toUpperCase(Locale.ROOT);
    }

    @Override
    public Optional<PaymentRedirect> checkout(String merchantTradeNo, BigDecimal amount, String description) {
        requireConfigured();
        if (amount.signum() <= 0) {
            throw new BusinessException("付款金額必須大於零", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String totalAmount;
        try {
            totalAmount = amount.setScale(0, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException ex) {
            throw new BusinessException("綠界付款金額必須為整數", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("MerchantID", properties.getMerchantId());
        fields.put("MerchantTradeNo", merchantTradeNo);
        fields.put("MerchantTradeDate", LocalDateTime.now(TAIPEI).format(TRADE_DATE));
        fields.put("PaymentType", "aio");
        fields.put("TotalAmount", totalAmount);
        fields.put("TradeDesc", description);
        fields.put("ItemName", description);
        fields.put("ReturnURL", properties.getCallbackUrl());
        fields.put("ClientBackURL", properties.getReturnUrl());
        fields.put("ChoosePayment", "Credit");
        fields.put("EncryptType", "1");
        fields.put("CheckMacValue", calculateCheckMacValue(fields));
        return Optional.of(new PaymentRedirect(properties.getPaymentUrl(), Map.copyOf(fields)));
    }

    @Override
    public Optional<VerifiedPaymentCallback> verifyCallback(Map<String, String> parameters) {
        try {
            String supplied = parameters.get("CheckMacValue");
            if (supplied == null || !MessageDigest.isEqual(
                    calculateCheckMacValue(parameters).getBytes(StandardCharsets.US_ASCII),
                    supplied.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
                return Optional.empty();
            }
            // Authentic from here on, but still only accept our own merchant and well-formed values.
            if (!properties.getMerchantId().equals(parameters.get("MerchantID"))) return Optional.empty();
            String tradeAmt = parameters.get("TradeAmt");
            if (tradeAmt == null || !DIGITS.matcher(tradeAmt).matches()) return Optional.empty();
            // A payment made from ECPay's merchant back-office "simulate payment" must never mark a real order paid;
            // it is only meaningful against the stage environment.
            if ("1".equals(parameters.get("SimulatePaid")) && !properties.getPaymentUrl().contains("payment-stage.")) {
                return Optional.empty();
            }
            String tradeNo = required(parameters, "MerchantTradeNo");
            String providerRef = required(parameters, "TradeNo");
            PaymentResult result = "1".equals(required(parameters, "RtnCode")) ? PaymentResult.SUCCESS : PaymentResult.FAILED;
            return Optional.of(new VerifiedPaymentCallback(tradeNo, new BigDecimal(tradeAmt), result, providerRef));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Map<String, String> simulatedCallback(String merchantTradeNo, BigDecimal amount, PaymentResult result,
                                                 String providerRef) {
        throw new UnsupportedOperationException("ECPay callbacks cannot be simulated");
    }

    /** Visible for deterministic conformance tests using ECPay's published vector. */
    public String calculateCheckMacValue(Map<String, String> parameters) {
        requireConfigured();
        // ECPay sorts parameter names A-Z ignoring case; credit-card callbacks mix PascalCase with lowercase fields
        // (card4no, auth_code, amount, ...), so a case-sensitive sort would reject every real callback.
        Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        parameters.forEach((key, value) -> {
            if (!"CheckMacValue".equalsIgnoreCase(key) && value != null) sorted.put(key, value);
        });
        StringBuilder raw = new StringBuilder("HashKey=").append(properties.getHashKey());
        sorted.forEach((key, value) -> raw.append('&').append(key).append('=').append(value));
        raw.append("&HashIV=").append(properties.getHashIv());
        String encoded = URLEncoder.encode(raw.toString(), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT)
                // Align Java's RFC 1866 encoding with ECPay's documented .NET-compatible table.
                .replace("%2d", "-").replace("%5f", "_").replace("%2e", ".")
                .replace("%21", "!").replace("%2a", "*").replace("%28", "(").replace("%29", ")");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02X", value));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void requireConfigured() {
        if (!properties.isComplete()) {
            throw new BusinessException("付款服務尚未設定", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static String required(Map<String, String> parameters, String name) {
        String value = parameters.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
