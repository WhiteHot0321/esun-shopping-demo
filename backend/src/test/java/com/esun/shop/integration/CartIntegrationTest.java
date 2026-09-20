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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class CartIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM order_detail");
        jdbc.update("DELETE FROM order_request");
        jdbc.update("DELETE FROM shop_order");
        jdbc.update("DELETE FROM shopping_cart");
        jdbc.update("DELETE FROM shipping_address");
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM member");
        jdbc.update("UPDATE product SET quantity = CASE product_id WHEN 'P001' THEN 5 WHEN 'P002' THEN 50 ELSE quantity END");
    }

    @Test
    void crudAccumulatesAndIsolatesCartByJwtOwner() throws Exception {
        String owner = register("cart-owner@example.com");
        String stranger = register("cart-stranger@example.com");

        long itemId = add(owner, "P001", 2, 2);
        add(owner, "P001", 2, 4);
        mvc.perform(get("/api/cart").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(itemId))
                .andExpect(jsonPath("$.data[0].productId").value("P001"))
                .andExpect(jsonPath("$.data[0].quantity").value(4));
        mvc.perform(get("/api/cart").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(put("/api/cart/items/{id}", itemId).header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/cart/items/{id}", itemId).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/cart/items/{id}", itemId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.quantity").value(3));
        mvc.perform(delete("/api/cart/items/{id}", itemId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopping_cart", Integer.class)).isZero();
    }

    @Test
    void rejectsInvalidProductsQuantitiesAndStockOverflow() throws Exception {
        String token = register("cart-validation@example.com");
        mvc.perform(post("/api/cart/add").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(itemJson("P001", 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/cart/add").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(itemJson("MISSING", 1)))
                .andExpect(status().isNotFound());
        add(token, "P001", 4, 4);
        mvc.perform(post("/api/cart/add").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(itemJson("P001", 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("商品庫存不足"));
        assertThat(jdbc.queryForObject("SELECT quantity FROM shopping_cart", Integer.class)).isEqualTo(4);
    }

    @Test
    void checkoutUsesServerCartAndClearsOnlyAfterSuccessfulOrder() throws Exception {
        String token = register("cart-checkout@example.com");
        add(token, "P001", 2, 2);
        long addressId = createAddress(token);
        String stranger = register("cart-checkout-stranger@example.com");
        long strangerAddressId = createAddress(stranger);

        mvc.perform(post("/api/cart/checkout").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutJson(strangerAddressId)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopping_cart", Integer.class)).isEqualTo(1);

        String response = mvc.perform(post("/api/cart/checkout").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutJson(addressId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String orderId = mapper.readTree(response).path("data").path("orderId").asText();
        assertThat(orderId).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopping_cart", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT member_id FROM shop_order WHERE order_id = ?",
                String.class, orderId)).isEqualTo("cart-checkout@example.com");
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_detail WHERE order_id = ?",
                Integer.class, orderId)).isEqualTo(2);
    }

    @Test
    void concurrentCheckoutWithDifferentRequestIdsConsumesCartExactlyOnce() throws Exception {
        String token = register("cart-concurrent@example.com");
        add(token, "P002", 1, 1);
        long addressId = createAddress(token);
        String first = checkoutJson(addressId);
        String second = checkoutJson(addressId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var calls = java.util.List.of(first, second).stream().map(body -> pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return mvc.perform(post("/api/cart/checkout").header("Authorization", bearer(token))
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var statuses = calls.stream().map(future -> {
                try { return future.get(20, TimeUnit.SECONDS); }
                catch (Exception ex) { throw new AssertionError(ex); }
            }).toList();
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_order WHERE member_id = 'cart-concurrent@example.com'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopping_cart", Integer.class)).isZero();
    }

    private String register(String email) throws Exception {
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("token").asText();
    }

    private long add(String token, String productId, int quantity, int expectedQuantity) throws Exception {
        String response = mvc.perform(post("/api/cart/add").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(itemJson(productId, quantity)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.quantity").value(expectedQuantity))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("id").asLong();
    }

    private long createAddress(String token) throws Exception {
        String body = mapper.writeValueAsString(Map.of("label", "住家", "receiverName", "王小明",
                "phone", "0912345678", "postalCode", "100", "address", "台北市測試路 1 號",
                "isDefault", true));
        String response = mvc.perform(post("/api/member/addresses").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("id").asLong();
    }

    private String itemJson(String productId, int quantity) throws Exception {
        return mapper.writeValueAsString(Map.of("productId", productId, "quantity", quantity));
    }

    private String checkoutJson(long addressId) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "requestId", UUID.randomUUID().toString(), "shippingAddressId", addressId));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
