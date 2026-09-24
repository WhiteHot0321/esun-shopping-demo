package com.esun.shop.integration;

import com.esun.shop.model.PaymentResult;
import com.esun.shop.service.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The default deployment (no {@code PAYMENT_SANDBOX_ENABLED}): payments cannot be started, the buyer-facing sandbox
 * shortcut does not exist, and even a correctly signed callback is refused - so the well-known dev secret in
 * application.yml cannot be used to mark orders paid on an instance that never turned payments on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.sandbox.enabled=false")
class PaymentDisabledIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentGateway gateway;

    @Test
    void nothingPaymentRelatedWorksWhilePaymentsAreOff() throws Exception {
        String email = "pay-off-" + System.nanoTime() + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(login).path("data").path("token").asText();

        mvc.perform(post("/api/orders/{id}/payment", "any").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/payments/{no}/sandbox-result", "PAYany").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound());

        String signature = gateway.sign("PAYany", new BigDecimal("1.00"), PaymentResult.SUCCESS, null);
        mvc.perform(post("/api/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("merchantTradeNo", "PAYany", "amount", 1,
                                "result", "SUCCESS", "signature", signature))))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE merchant_trade_no = 'PAYany'", Integer.class))
                .isZero();
    }
}
