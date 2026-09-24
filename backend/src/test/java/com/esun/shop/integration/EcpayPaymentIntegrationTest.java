package com.esun.shop.integration;

import com.esun.shop.service.EcpayPaymentGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real-provider path end to end against a real MySQL: ECPay's form-encoded, CheckMacValue-signed callback drives
 * the very same order/payment state machine as the sandbox. Uses ECPay's public stage test account; no network calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "payment.provider=ecpay",
        "ecpay.merchant-id=3002607",
        "ecpay.hash-key=pwFHCqoQZGmho4w6",
        "ecpay.hash-iv=EkRm7iFT261dpevs",
        "ecpay.payment-url=https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5",
        "ecpay.callback-url=https://merchant.example/api/payments/ecpay/callback",
        "ecpay.return-url=https://merchant.example/return"})
class EcpayPaymentIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EcpayPaymentGateway gateway;

    @Test
    void startPaymentReturnsASignedEcpayFormForTheServerPricedOrder() throws Exception {
        Fixture f = fixture(2, 100);
        JsonNode data = start(f).path("data");

        assertThat(data.path("simulatable").asBoolean()).isFalse();
        assertThat(data.path("provider").asText()).isEqualTo("ecpay");
        JsonNode redirect = data.path("redirect");
        assertThat(redirect.path("actionUrl").asText())
                .isEqualTo("https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5");
        Map<String, String> fields = new LinkedHashMap<>();
        redirect.path("fields").fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue().asText()));
        assertThat(fields.get("MerchantTradeNo")).isEqualTo(data.path("merchantTradeNo").asText()).matches("E[0-9A-F]{19}");
        assertThat(fields).containsEntry("TotalAmount", "200").containsEntry("ChoosePayment", "Credit");
        assertThat(fields.get("CheckMacValue")).isEqualTo(gateway.calculateCheckMacValue(fields));
    }

    @Test
    void payingAgainOpensAFreshTradeNoBecauseEcpayRejectsAReusedOneAndALateSuccessOnTheOldOneStillCounts() throws Exception {
        Fixture f = fixture(1, 100);
        String first = start(f).path("data").path("merchantTradeNo").asText();
        String second = start(f).path("data").path("merchantTradeNo").asText();

        assertThat(second).isNotEqualTo(first).matches("E[0-9A-F]{19}");
        assertThat(paymentStatus(first)).isEqualTo("FAILED");
        assertThat(paymentStatus(second)).isEqualTo("INITIATED");
        assertThat(count("SELECT COUNT(*) FROM payment WHERE order_id = ? AND status = 'INITIATED'", f.orderId)).isEqualTo(1);

        // the buyer had left the first ECPay page open and paid there: the order is unpaid, so it counts
        ecpayCallback(callback(first, "100", "1", "2401010000000009"))
                .andExpect(status().isOk()).andExpect(content().string("1|OK"));
        assertThat(paymentStatus(first)).isEqualTo("SUCCEEDED");
        assertThat(paymentStatus(second)).isEqualTo("FAILED");
        assertThat(count("SELECT pay_status FROM shop_order WHERE order_id = ?", f.orderId)).isEqualTo(1);

        // ... and if the newer page is then also paid, that second charge is flagged for refund, not double-counted
        ecpayCallback(callback(second, "100", "1", "2401010000000010")).andExpect(content().string("1|OK"));
        assertThat(paymentStatus(second)).isEqualTo("REFUND_REQUIRED");
        assertThat(count("SELECT pay_status FROM shop_order WHERE order_id = ?", f.orderId)).isEqualTo(1);
    }

    @Test
    void aRealCreditCardCallbackWithLowercaseFieldsIsAccepted() throws Exception {
        Fixture f = fixture(1, 100);
        String tradeNo = start(f).path("data").path("merchantTradeNo").asText();
        Map<String, String> values = callback(tradeNo, "100", "1", "2401010000000011");
        values.remove("CheckMacValue");
        values.put("card4no", "1234");
        values.put("auth_code", "777777");
        values.put("process_date", "2024/01/01 10:00:00");
        values.put("CheckMacValue", gateway.calculateCheckMacValue(values));
        ecpayCallback(values).andExpect(status().isOk()).andExpect(content().string("1|OK"));
        assertThat(paymentStatus(tradeNo)).isEqualTo("SUCCEEDED");
    }

    @Test
    void aSignedSuccessCallbackMarksTheOrderPaidAndIsAcknowledgedOnReplay() throws Exception {
        Fixture f = fixture(1, 100);
        String tradeNo = start(f).path("data").path("merchantTradeNo").asText();

        ecpayCallback(callback(tradeNo, "100", "1", "2401010000000001"))
                .andExpect(status().isOk()).andExpect(content().string("1|OK"));
        assertThat(paymentStatus(tradeNo)).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT pay_status FROM shop_order WHERE order_id = ?", f.orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT provider_ref FROM payment WHERE merchant_trade_no = ?", String.class, tradeNo))
                .isEqualTo("2401010000000001");

        // ECPay retries until it sees 1|OK: a replay must be acknowledged and change nothing
        ecpayCallback(callback(tradeNo, "100", "1", "2401010000000001"))
                .andExpect(status().isOk()).andExpect(content().string("1|OK"));
        assertThat(paymentStatus(tradeNo)).isEqualTo("SUCCEEDED");
    }

    @Test
    void forgedOrMalformedCallbacksAreRejectedWithoutChangingAnything() throws Exception {
        Fixture f = fixture(1, 100);
        String tradeNo = start(f).path("data").path("merchantTradeNo").asText();

        Map<String, String> forged = callback(tradeNo, "100", "1", "2401010000000001");
        forged.put("CheckMacValue", "0".repeat(64));
        ecpayCallback(forged).andExpect(status().isBadRequest()).andExpect(content().string("0|ERROR"));

        Map<String, String> tampered = callback(tradeNo, "100", "1", "2401010000000001");
        tampered.put("TradeAmt", "1"); // signature no longer covers the values
        ecpayCallback(tampered).andExpect(status().isBadRequest()).andExpect(content().string("0|ERROR"));

        Map<String, String> otherMerchant = callback(tradeNo, "100", "1", "2401010000000001");
        otherMerchant.put("MerchantID", "9999999");
        otherMerchant.put("CheckMacValue", gateway.calculateCheckMacValue(otherMerchant));
        ecpayCallback(otherMerchant).andExpect(status().isBadRequest());

        Map<String, String> fractional = callback(tradeNo, "12.5", "1", "2401010000000001");
        ecpayCallback(fractional).andExpect(status().isBadRequest());

        // no signature at all, and a duplicated parameter
        mvc.perform(post("/api/payments/ecpay/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("MerchantTradeNo", tradeNo).param("RtnCode", "1")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/payments/ecpay/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("MerchantTradeNo", tradeNo).param("MerchantTradeNo", "other")).andExpect(status().isBadRequest());

        assertThat(paymentStatus(tradeNo)).isEqualTo("INITIATED");
        assertThat(count("SELECT pay_status FROM shop_order WHERE order_id = ?", f.orderId)).isZero();
    }

    @Test
    void aDeclinedCardClosesTheAttemptAndTheBuyerCanRetryWithANewTradeNo() throws Exception {
        Fixture f = fixture(1, 100);
        String first = start(f).path("data").path("merchantTradeNo").asText();
        ecpayCallback(callback(first, "100", "10100058", "2401010000000002"))
                .andExpect(status().isOk()).andExpect(content().string("1|OK"));
        assertThat(paymentStatus(first)).isEqualTo("FAILED");
        assertThat(count("SELECT pay_status FROM shop_order WHERE order_id = ?", f.orderId)).isZero();

        String second = start(f).path("data").path("merchantTradeNo").asText();
        assertThat(second).isNotEqualTo(first).matches("E[0-9A-F]{19}");
        ecpayCallback(callback(second, "100", "1", "2401010000000003")).andExpect(content().string("1|OK"));
        assertThat(count("SELECT pay_status FROM shop_order WHERE order_id = ?", f.orderId)).isEqualTo(1);
    }

    @Test
    void anAuthenticCallbackForADifferentAmountIsAcknowledgedButParkedForRefund() throws Exception {
        Fixture f = fixture(1, 100);
        String tradeNo = start(f).path("data").path("merchantTradeNo").asText();
        ecpayCallback(callback(tradeNo, "99", "1", "2401010000000004"))
                .andExpect(status().isOk()).andExpect(content().string("1|OK"));
        assertThat(paymentStatus(tradeNo)).isEqualTo("REFUND_REQUIRED");
        assertThat(count("SELECT pay_status FROM shop_order WHERE order_id = ?", f.orderId)).isZero();
    }

    @Test
    void anAmountEcpayCannotChargeIsRefusedAndLeavesNoAttemptBehind() throws Exception {
        Fixture f = fixture(1, 10.5);
        mvc.perform(post("/api/orders/{id}/payment", f.orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(count("SELECT COUNT(*) FROM payment WHERE order_id = ?", f.orderId)).isZero();
    }

    @Test
    void thereIsNoSandboxShortcutAndTheJsonSandboxCallbackDoesNotWork() throws Exception {
        Fixture f = fixture(1, 100);
        String tradeNo = start(f).path("data").path("merchantTradeNo").asText();
        mvc.perform(post("/api/payments/{no}/sandbox-result", tradeNo).header("Authorization", bearer(f.buyer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("merchantTradeNo", tradeNo, "amount", 100,
                                "result", "SUCCESS", "signature", "0".repeat(64)))))
                .andExpect(status().isBadRequest());
        assertThat(paymentStatus(tradeNo)).isEqualTo("INITIATED");
    }

    // ---- helpers ----

    private record Fixture(String buyer, String orderId) { }

    private Fixture fixture(int quantity, double price) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String seller = token("ec-seller-" + tag + "@example.com", "SELLER");
        String buyer = token("ec-buyer-" + tag + "@example.com", "BUYER");
        String productId = "EC-" + tag;
        mvc.perform(post("/api/admin/products").header("Authorization", bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("productId", productId, "productName", "Item " + productId,
                                "price", price, "quantity", 10))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/member/addresses").header("Authorization", bearer(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("label", "住家", "receiverName", "王小明",
                                "phone", "0912-345-678", "postalCode", "100", "address", "台北市中正區測試路 1 號",
                                "isDefault", true))))
                .andExpect(status().isOk());
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("items", List.of(Map.of("productId", productId, "quantity", quantity)));
        String response = mvc.perform(post("/api/orders").header("Authorization", bearer(buyer))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Fixture(buyer, mapper.readTree(response).path("data").path("orderId").asText());
    }

    private JsonNode start(Fixture f) throws Exception {
        String body = mvc.perform(post("/api/orders/{id}/payment", f.orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    /** What ECPay would POST to our callback URL, signed with the stage HashKey/HashIV. */
    private Map<String, String> callback(String tradeNo, String amount, String rtnCode, String ecpayTradeNo) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MerchantID", "3002607");
        values.put("MerchantTradeNo", tradeNo);
        values.put("TradeAmt", amount);
        values.put("TradeNo", ecpayTradeNo);
        values.put("RtnCode", rtnCode);
        values.put("RtnMsg", "test");
        values.put("PaymentType", "credit_CreditCard");
        values.put("CheckMacValue", gateway.calculateCheckMacValue(values));
        return values;
    }

    private ResultActions ecpayCallback(Map<String, String> values) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/payments/ecpay/callback")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        values.forEach(request::param);
        return mvc.perform(request);
    }

    private String paymentStatus(String tradeNo) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE merchant_trade_no = ?", String.class, tradeNo);
    }

    private int count(String sql, String orderId) {
        return jdbc.queryForObject(sql, Integer.class, orderId);
    }

    private String token(String email, String role) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk());
        if (!"BUYER".equals(role)) jdbc.update("UPDATE member SET role = ? WHERE email = ?", role, email);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
