package com.esun.shop.payment;

import com.esun.shop.config.EcpayProperties;
import com.esun.shop.exception.BusinessException;
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

/** ECPay AioCheckOut SHA-256 CheckMacValue implementation. */
@Component
public class EcpayPaymentGateway implements PaymentGateway {
    private static final DateTimeFormatter TRADE_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private final EcpayProperties properties;

    public EcpayPaymentGateway(EcpayProperties properties) {
        this.properties = properties;
    }

    @Override
    public PaymentForm createPaymentForm(String merchantTradeNo, BigDecimal amount, String orderDescription) {
        requireConfigured();
        String totalAmount;
        try {
            totalAmount = amount.setScale(0, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException ex) {
            throw new BusinessException("綠界付款金額必須為整數", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (amount.signum() <= 0) {
            throw new BusinessException("付款金額必須大於零", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("MerchantID", properties.getMerchantId());
        fields.put("MerchantTradeNo", merchantTradeNo);
        fields.put("MerchantTradeDate", LocalDateTime.now(TAIPEI).format(TRADE_DATE));
        fields.put("PaymentType", "aio");
        fields.put("TotalAmount", totalAmount);
        fields.put("TradeDesc", orderDescription);
        fields.put("ItemName", orderDescription);
        fields.put("ReturnURL", properties.getCallbackUrl());
        fields.put("ClientBackURL", properties.getReturnUrl());
        fields.put("ChoosePayment", "ALL");
        fields.put("EncryptType", "1");
        fields.put("CheckMacValue", calculateCheckMacValue(fields));
        return new PaymentForm(properties.getPaymentUrl(), Map.copyOf(fields));
    }

    @Override
    public Optional<VerifiedPaymentCallback> verifyCallback(Map<String, String> parameters) {
        if (!properties.isComplete()) return Optional.empty();
        String supplied = parameters.get("CheckMacValue");
        if (supplied == null || !MessageDigest.isEqual(
                calculateCheckMacValue(parameters).getBytes(StandardCharsets.US_ASCII),
                supplied.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new VerifiedPaymentCallback(
                    required(parameters, "MerchantID"),
                    required(parameters, "MerchantTradeNo"),
                    new BigDecimal(required(parameters, "TradeAmt")),
                    required(parameters, "TradeNo"),
                    "1".equals(required(parameters, "RtnCode"))));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    @Override
    public String merchantId() {
        return properties.getMerchantId();
    }

    /** Visible for deterministic conformance tests using ECPay's published vector. */
    public String calculateCheckMacValue(Map<String, String> parameters) {
        requireConfigured();
        Map<String, String> sorted = new TreeMap<>();
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
