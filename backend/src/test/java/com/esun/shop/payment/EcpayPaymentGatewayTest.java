package com.esun.shop.payment;

import com.esun.shop.config.EcpayProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EcpayPaymentGatewayTest {
    private final EcpayPaymentGateway gateway = new EcpayPaymentGateway(new EcpayProperties(
            "3002607", "pwFHCqoQZGmho4w6", "EkRm7iFT261dpevs",
            "https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5",
            "https://merchant.example/callback", "https://merchant.example/return"));

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
    void verifyCallback_rejectsWrongSignature_andAcceptsSignedSuccessfulCallback() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MerchantID", "3002607");
        values.put("MerchantTradeNo", "E0123456789ABCDEF012");
        values.put("TradeAmt", "100");
        values.put("TradeNo", "2401010000000001");
        values.put("RtnCode", "1");
        values.put("RtnMsg", "Succeeded");
        values.put("CheckMacValue", gateway.calculateCheckMacValue(values));

        assertThat(gateway.verifyCallback(values)).hasValueSatisfying(callback -> {
            assertThat(callback.successful()).isTrue();
            assertThat(callback.amount()).isEqualByComparingTo("100");
        });
        values.put("CheckMacValue", "NOT_A_SIGNATURE");
        assertThat(gateway.verifyCallback(values)).isEmpty();
    }
}
