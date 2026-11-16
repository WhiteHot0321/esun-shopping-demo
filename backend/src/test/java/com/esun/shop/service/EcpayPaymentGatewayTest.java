package com.esun.shop.service;

import com.esun.shop.config.EcpayProperties;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.PaymentResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the ECPay adapter, using ECPay's public stage test account and published SHA-256 vector. */
class EcpayPaymentGatewayTest {
    private final EcpayPaymentGateway gateway = new EcpayPaymentGateway(new EcpayProperties(
            "3002607", "pwFHCqoQZGmho4w6", "EkRm7iFT261dpevs",
            "https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5",
            "https://merchant.example/callback", "https://merchant.example/return"));

    private Map<String, String> signedCallback(Map<String, String> overrides) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MerchantID", "3002607");
        values.put("MerchantTradeNo", "E0123456789ABCDEF012");
        values.put("TradeAmt", "100");
        values.put("TradeNo", "2401010000000001");
        values.put("RtnCode", "1");
        values.put("RtnMsg", "Succeeded");
        values.putAll(overrides);
        values.put("CheckMacValue", gateway.calculateCheckMacValue(values));
        return values;
    }

    @Test
    void calculateCheckMacValue_matchesEcpayPublishedSha256Vector() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MerchantID", "3002607");
        values.put("MerchantTradeNo", "ecpay20230312153023");
        values.put("MerchantTradeDate", "2023/03/12 15:30:23");
        values.put("PaymentType", "aio");
        values.put("TotalAmount", "30000");
        values.put("TradeDesc", "促銷方案");
        values.put("ItemName", "Apple iphone 15");
        values.put("ReturnURL", "https://www.ecpay.com.tw/receive.php");
        values.put("ChoosePayment", "ALL");
        values.put("EncryptType", "1");

        assertThat(gateway.calculateCheckMacValue(values))
                .isEqualTo("6C51C9E6888DE861FD62FB1DD17029FC742634498FD813DC43D4243B5685B840");
    }

    @Test
    void verifyCallbackAcceptsASignedSuccessAndMapsRtnCode() {
        assertThat(gateway.verifyCallback(signedCallback(Map.of()))).hasValueSatisfying(callback -> {
            assertThat(callback.result()).isEqualTo(PaymentResult.SUCCESS);
            assertThat(callback.amount()).isEqualByComparingTo("100");
            assertThat(callback.merchantTradeNo()).isEqualTo("E0123456789ABCDEF012");
            assertThat(callback.providerRef()).isEqualTo("2401010000000001");
        });
        assertThat(gateway.verifyCallback(signedCallback(Map.of("RtnCode", "10100058"))))
                .hasValueSatisfying(callback -> assertThat(callback.result()).isEqualTo(PaymentResult.FAILED));
    }

    @Test
    void verifyCallbackRejectsWrongSignatureForeignMerchantAndMalformedValues() {
        Map<String, String> bad = signedCallback(Map.of());
        bad.put("CheckMacValue", "NOT_A_SIGNATURE");
        assertThat(gateway.verifyCallback(bad)).isEmpty();

        Map<String, String> tamperedAmount = signedCallback(Map.of());
        tamperedAmount.put("TradeAmt", "1");
        assertThat(gateway.verifyCallback(tamperedAmount)).isEmpty();

        assertThat(gateway.verifyCallback(signedCallback(Map.of("MerchantID", "9999999")))).isEmpty();
        assertThat(gateway.verifyCallback(signedCallback(Map.of("TradeAmt", "1E+100000000")))).isEmpty();
        assertThat(gateway.verifyCallback(signedCallback(Map.of("TradeAmt", "-5")))).isEmpty();
        assertThat(gateway.verifyCallback(signedCallback(Map.of("TradeAmt", "12.5")))).isEmpty();
        assertThat(gateway.verifyCallback(Map.of())).isEmpty();
    }

    @Test
    void checkoutBuildsASignedCreditCardOnlyFormWithAnEcpayLegalTradeNo() {
        String tradeNo = gateway.newMerchantTradeNo();
        assertThat(tradeNo).matches("E[0-9A-F]{19}");

        var redirect = gateway.checkout(tradeNo, new BigDecimal("250.00"), "ESUN order Ms1").orElseThrow();
        assertThat(redirect.actionUrl()).isEqualTo("https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5");
        Map<String, String> fields = redirect.fields();
        assertThat(fields).containsEntry("MerchantID", "3002607").containsEntry("MerchantTradeNo", tradeNo)
                .containsEntry("TotalAmount", "250").containsEntry("ChoosePayment", "Credit")
                .containsEntry("ReturnURL", "https://merchant.example/callback")
                .containsEntry("ClientBackURL", "https://merchant.example/return");
        assertThat(fields.get("CheckMacValue")).isEqualTo(gateway.calculateCheckMacValue(fields));
    }

    @Test
    void checkoutRefusesAmountsEcpayCannotCharge() {
        assertThatThrownBy(() -> gateway.checkout("E1", new BigDecimal("10.5"), "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("整數");
        assertThatThrownBy(() -> gateway.checkout("E1", BigDecimal.ZERO, "x")).isInstanceOf(BusinessException.class);
    }

    @Test
    void anIncompleteConfigurationFailsAtStartupInsteadOfAtFirstPayment() {
        assertThatThrownBy(() -> new EcpayPaymentGateway(new EcpayProperties("", "", "", "", "", "")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EcpayPaymentGateway(new EcpayProperties("3002607", "k", "iv", "https://p", "", "https://r")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void checkMacValueSortsParameterNamesIgnoringCaseAsEcpayDoes() throws Exception {
        // Real credit-card callbacks mix PascalCase (MerchantID) with lowercase names (amount, card4no, auth_code).
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MerchantID", "3002607");
        values.put("card4no", "1234");
        values.put("amount", "100");
        values.put("auth_code", "777777");
        // Case-insensitive A-Z order: amount, auth_code, card4no, MerchantID (a case-sensitive sort puts MerchantID first).
        String raw = "HashKey=pwFHCqoQZGmho4w6&amount=100&auth_code=777777&card4no=1234&MerchantID=3002607&HashIV=EkRm7iFT261dpevs";
        String encoded = java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT)
                .replace("%2d", "-").replace("%5f", "_").replace("%2e", ".")
                .replace("%21", "!").replace("%2a", "*").replace("%28", "(").replace("%29", ")");
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder expected = new StringBuilder();
        for (byte b : digest) expected.append(String.format("%02X", b));

        assertThat(gateway.calculateCheckMacValue(values)).isEqualTo(expected.toString());
    }

    @Test
    void aCallbackCarryingLowercaseCreditCardFieldsStillVerifies() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("card4no", "1234");
        extra.put("auth_code", "777777");
        extra.put("amount", "100");
        extra.put("process_date", "2024/01/01 10:00:00");
        assertThat(gateway.verifyCallback(signedCallback(extra))).isPresent();
    }

    @Test
    void simulatedPaymentsAreRefusedUnlessTheProviderIsTheStageEnvironment() {
        assertThat(gateway.verifyCallback(signedCallback(Map.of("SimulatePaid", "1")))).isPresent(); // stage URL
        EcpayPaymentGateway production = new EcpayPaymentGateway(new EcpayProperties(
                "3002607", "pwFHCqoQZGmho4w6", "EkRm7iFT261dpevs", "https://payment.ecpay.com.tw/Cashier/AioCheckOut/V5",
                "https://merchant.example/callback", "https://merchant.example/return"));
        Map<String, String> simulated = new LinkedHashMap<>(Map.of("MerchantID", "3002607",
                "MerchantTradeNo", "E0123456789ABCDEF012", "TradeAmt", "100", "TradeNo", "T1", "RtnCode", "1",
                "SimulatePaid", "1"));
        simulated.put("CheckMacValue", production.calculateCheckMacValue(simulated));
        assertThat(production.verifyCallback(simulated)).isEmpty();
        simulated.put("SimulatePaid", "0");
        simulated.put("CheckMacValue", production.calculateCheckMacValue(simulated));
        assertThat(production.verifyCallback(simulated)).isPresent();
    }

    @Test
    void aCallbackWithoutRtnCodeIsNotAResultAndAttemptsCannotBeResumed() {
        Map<String, String> noRtn = new LinkedHashMap<>(Map.of("MerchantID", "3002607",
                "MerchantTradeNo", "E0123456789ABCDEF012", "TradeAmt", "100", "TradeNo", "T1"));
        noRtn.put("CheckMacValue", gateway.calculateCheckMacValue(noRtn));
        assertThat(gateway.verifyCallback(noRtn)).isEmpty();
        assertThat(gateway.canResumeAttempt()).isFalse();
    }

    /**
     * The full field set ECPay documents for a credit-card ReturnURL callback: empty optional fields, spaces, slashes
     * and a Chinese RtnMsg. The expected value was computed by a separate Python implementation
     * (sorted case-insensitively, urllib quote_plus with safe "-_.!*()", lowercased, SHA-256), not by this class.
     */
    @Test
    void aFullCreditCardCallbackMatchesAnIndependentlyComputedCheckMacValue() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("CustomField1", "");
        values.put("CustomField2", "");
        values.put("CustomField3", "");
        values.put("CustomField4", "");
        values.put("MerchantID", "3002607");
        values.put("MerchantTradeNo", "E0123456789ABCDEF012");
        values.put("PaymentDate", "2024/01/01 10:00:05");
        values.put("PaymentType", "Credit_CreditCard");
        values.put("PaymentTypeChargeFee", "2");
        values.put("RtnCode", "1");
        values.put("RtnMsg", "交易成功");
        values.put("SimulatePaid", "0");
        values.put("StoreID", "");
        values.put("TradeAmt", "100");
        values.put("TradeDate", "2024/01/01 09:59:30");
        values.put("TradeNo", "2401010959301234");
        String independent = "05C3A1624F6DFD954A3ABB05F0FECA1B9A4EA16FB4DE3C2A3B765ACCC0F5589D";
        assertThat(gateway.calculateCheckMacValue(values)).isEqualTo(independent);

        values.put("CheckMacValue", independent.toLowerCase(java.util.Locale.ROOT)); // case of the MAC itself is irrelevant
        assertThat(gateway.verifyCallback(values)).hasValueSatisfying(callback -> {
            assertThat(callback.result()).isEqualTo(PaymentResult.SUCCESS);
            assertThat(callback.providerRef()).isEqualTo("2401010959301234");
        });
    }

    /**
     * The MAC is computed over names compared ignoring case, the values are then read by exact name. Two names that
     * differ only in case would let the MAC cover one value while the shop acts on the other.
     */
    @Test
    void aSignedFieldCannotBeShadowedByACaseVariantOfItsName() {
        Map<String, String> genuineDecline = signedCallback(Map.of("RtnCode", "10100058"));

        Map<String, String> forged = new LinkedHashMap<>();
        forged.put("RtnCode", "1"); // what the shop would read
        genuineDecline.forEach((name, value) -> {
            if (!"RtnCode".equals(name)) forged.put(name, value);
        });
        forged.put("rtncode", "10100058"); // what the genuine MAC covers
        assertThat(gateway.verifyCallback(forged)).isEmpty();

        Map<String, String> otherTrade = new LinkedHashMap<>();
        otherTrade.put("MerchantTradeNo", "E9999999999999999999");
        signedCallback(Map.of()).forEach((name, value) -> {
            if (!"MerchantTradeNo".equals(name)) otherTrade.put(name, value);
        });
        otherTrade.put("MERCHANTTRADENO", "E0123456789ABCDEF012");
        assertThat(gateway.verifyCallback(otherTrade)).isEmpty();

        Map<String, String> twoMacs = signedCallback(Map.of());
        twoMacs.put("checkmacvalue", "0".repeat(64));
        assertThat(gateway.verifyCallback(twoMacs)).isEmpty();
    }

    @Test
    void ecpayCannotBeSimulated() {
        assertThat(gateway.supportsSimulation()).isFalse();
        assertThatThrownBy(() -> gateway.simulatedCallback("E1", BigDecimal.ONE, PaymentResult.SUCCESS, null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
