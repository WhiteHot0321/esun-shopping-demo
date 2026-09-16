package com.esun.shop.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AuthIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void registerLoginAndProtectedProductUseRealDatabaseAndJwt() throws Exception {
        String email = "phase2-" + UUID.randomUUID() + "@example.com";
        String credentials = mapper.writeValueAsString(Map.of("email", email, "password", "password123"));
        String registered = mvc.perform(post("/api/auth/register").contentType("application/json").content(credentials))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(registered).path("data").path("token").asText();
        String hash = jdbc.queryForObject("SELECT password_hash FROM member WHERE email = ?", String.class, email);
        assertThat(hash).isNotEqualTo("password123");
        assertThat(new BCryptPasswordEncoder().matches("password123", hash)).isTrue();
        mvc.perform(post("/api/auth/login").contentType("application/json").content(credentials))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").isNotEmpty());
        mvc.perform(post("/api/auth/register").contentType("application/json").content(credentials))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/login").contentType("application/json").content(
                mapper.writeValueAsString(Map.of("email", email, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized());
        String product = mapper.writeValueAsString(Map.of("productId", "AUTH-P2", "productName", "Auth product", "price", 10, "quantity", 2));
        mvc.perform(post("/api/products").contentType("application/json").content(product))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/products").header("Authorization", "Bearer " + token).contentType("application/json").content(product))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT quantity FROM product WHERE product_id = 'AUTH-P2'", Integer.class)).isEqualTo(2);
        mvc.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/other")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/products/available")).andExpect(status().isOk());
    }
}
