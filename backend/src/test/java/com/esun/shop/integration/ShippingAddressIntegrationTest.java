package com.esun.shop.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ShippingAddressIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM order_detail");
        jdbc.update("DELETE FROM order_request");
        jdbc.update("DELETE FROM order_status_history");
        jdbc.update("DELETE FROM shop_order");
        jdbc.update("DELETE FROM shipping_address");
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM member");
    }

    @Test
    void crudMaintainsOneDefaultAndIsolatesMembers() throws Exception {
        String owner = register("owner@example.com");
        String stranger = register("stranger@example.com");
        long home = createAddress(owner, "住家", false);
        long office = createAddress(owner, "公司", true);

        mvc.perform(get("/api/member/addresses").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(office))
                .andExpect(jsonPath("$.data[0].isDefault").value(true))
                .andExpect(jsonPath("$.data[1].isDefault").value(false));

        mvc.perform(put("/api/member/addresses/{id}", home)
                        .header("Authorization", bearer(stranger)).contentType(MediaType.APPLICATION_JSON)
                        .content(addressJson("偷改", false)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/member/addresses/{id}/set-default", home)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isDefault").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipping_address WHERE is_default = TRUE",
                Integer.class)).isEqualTo(1);

        mvc.perform(delete("/api/member/addresses/{id}", home).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT is_default FROM shipping_address WHERE id = ?",
                Boolean.class, office)).isTrue();
    }

    @Test
    void checkoutPersistsOwnedAddressUsesDefaultAndProtectsReferencedAddress() throws Exception {
        String owner = register("buyer@example.com");
        String stranger = register("other@example.com");
        long addressId = createAddress(owner, "住家", false);

        String orderId = createOrder(owner, "spoofed@example.com", addressId);
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT member_id, shipping_address_id FROM shop_order WHERE order_id = ?", orderId);
        assertThat(stored.get("member_id")).isEqualTo("buyer@example.com");
        assertThat(((Number) stored.get("shipping_address_id")).longValue()).isEqualTo(addressId);

        mvc.perform(delete("/api/member/addresses/{id}", addressId).header("Authorization", bearer(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("此地址已被訂單使用，無法刪除"));

        mvc.perform(post("/api/orders").header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON).content(orderJson("other@example.com", addressId)))
                .andExpect(status().isNotFound());

        String defaultOrder = mvc.perform(post("/api/orders").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(orderJson(null, null)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(defaultOrder).path("data").path("orderId").asText()).isNotBlank();
    }

    private String register(String email) throws Exception {
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("token").asText();
    }

    private long createAddress(String token, String label, boolean makeDefault) throws Exception {
        String response = mvc.perform(post("/api/member/addresses").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(addressJson(label, makeDefault)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("id").asLong();
    }

    private String createOrder(String token, String memberId, long addressId) throws Exception {
        String response = mvc.perform(post("/api/orders").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(orderJson(memberId, addressId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("orderId").asText();
    }

    private String addressJson(String label, boolean makeDefault) throws Exception {
        return mapper.writeValueAsString(Map.of("label", label, "receiverName", "王小明",
                "phone", "0912-345-678", "postalCode", "100", "address", "台北市中正區測試路 1 號",
                "isDefault", makeDefault));
    }

    private String orderJson(String memberId, Long addressId) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("requestId", UUID.randomUUID().toString());
        if (memberId != null) body.put("memberId", memberId);
        body.put("payStatus", "PENDING");
        if (addressId != null) body.put("shippingAddressId", addressId);
        body.put("items", java.util.List.of(Map.of("productId", "P001", "quantity", 1)));
        return mapper.writeValueAsString(body);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
