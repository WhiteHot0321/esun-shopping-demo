package com.esun.shop.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    @Test
    void changePasswordRequiresAuthAndUpdatesRealHash() throws Exception {
        String email = "changepw-" + UUID.randomUUID() + "@example.com";
        String registered = mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "original-pw"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(registered).path("data").path("token").asText();

        mvc.perform(post("/api/auth/change-password").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("currentPassword", "original-pw", "newPassword", "brand-new-pw"))))
                .andExpect(status().isUnauthorized());

        // 400, not 401: the JWT itself is valid, only the current-password field is wrong - a
        // 401 here would trip the frontend's global "session expired" interceptor.
        mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("currentPassword", "wrong-pw", "newPassword", "brand-new-pw"))))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("currentPassword", "original-pw", "newPassword", "brand-new-pw"))))
                .andExpect(status().isOk());

        String hash = jdbc.queryForObject("SELECT password_hash FROM member WHERE email = ?", String.class, email);
        assertThat(new BCryptPasswordEncoder().matches("brand-new-pw", hash)).isTrue();
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "original-pw"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "brand-new-pw"))))
                .andExpect(status().isOk());
    }

    @Test
    void forgotPasswordCreatesTokenOnlyForRealAccountsAndResetPasswordRejectsBadTokens() throws Exception {
        String email = "forgotpw-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "original-pw"))))
                .andExpect(status().isOk());

        Long memberId = jdbc.queryForObject("SELECT id FROM member WHERE email = ?", Long.class, email);

        // Unknown email: same 200 response, no row created - avoids account enumeration.
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com"))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk());
        Integer tokenCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM password_reset_token WHERE member_id = ? AND used_at IS NULL", Integer.class, memberId);
        assertThat(tokenCount).isEqualTo(1);

        mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("token", "not-a-real-token", "newPassword", "whatever-pw"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetPasswordConsumesTokenOnceAndRejectsExpiredToken() throws Exception {
        String email = "resetpw-" + UUID.randomUUID() + "@example.com";
        register(email, "original-pw");

        String validToken = "valid-" + UUID.randomUUID();
        insertResetToken(email, validToken, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));

        mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                        .content(resetBody(validToken, "replacement-pw")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                        .content(resetBody(validToken, "replayed-password")))
                .andExpect(status().isBadRequest());

        String hash = jdbc.queryForObject("SELECT password_hash FROM member WHERE email = ?", String.class, email);
        assertThat(new BCryptPasswordEncoder().matches("replacement-pw", hash)).isTrue();

        String expiredToken = "expired-" + UUID.randomUUID();
        insertResetToken(email, expiredToken, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                        .content(resetBody(expiredToken, "must-not-apply")))
                .andExpect(status().isBadRequest());
        String unchangedHash = jdbc.queryForObject("SELECT password_hash FROM member WHERE email = ?", String.class, email);
        assertThat(new BCryptPasswordEncoder().matches("replacement-pw", unchangedHash)).isTrue();
    }

    @Test
    void concurrentResetWithSameTokenAllowsExactlyOnePasswordChange() throws Exception {
        String email = "concurrent-reset-" + UUID.randomUUID() + "@example.com";
        register(email, "original-pw");
        String rawToken = "concurrent-" + UUID.randomUUID();
        insertResetToken(email, rawToken, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> first = () -> resetAfterBarrier(start, rawToken, "first-password");
            Callable<Integer> second = () -> resetAfterBarrier(start, rawToken, "second-password");
            Future<Integer> firstResult = executor.submit(first);
            Future<Integer> secondResult = executor.submit(second);

            assertThat(List.of(firstResult.get(), secondResult.get()))
                    .containsExactlyInAnyOrder(200, 400);
        } finally {
            executor.shutdownNow();
        }

        String hash = jdbc.queryForObject("SELECT password_hash FROM member WHERE email = ?", String.class, email);
        boolean firstWon = new BCryptPasswordEncoder().matches("first-password", hash);
        boolean secondWon = new BCryptPasswordEncoder().matches("second-password", hash);
        assertThat(firstWon ^ secondWon).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM password_reset_token prt JOIN member m ON m.id = prt.member_id "
                        + "WHERE m.email = ? AND prt.used_at IS NOT NULL",
                Integer.class, email)).isEqualTo(1);
    }

    private void register(String email, String password) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk());
    }

    private void insertResetToken(String email, String rawToken, LocalDateTime expiresAt) throws Exception {
        Long memberId = jdbc.queryForObject("SELECT id FROM member WHERE email = ?", Long.class, email);
        String tokenHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO password_reset_token (member_id, token_hash, expires_at) VALUES (?, ?, ?)",
                memberId, tokenHash, expiresAt);
    }

    private String resetBody(String rawToken, String newPassword) throws Exception {
        return mapper.writeValueAsString(Map.of("token", rawToken, "newPassword", newPassword));
    }

    private int resetAfterBarrier(CyclicBarrier start, String rawToken, String newPassword) throws Exception {
        start.await();
        return mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                        .content(resetBody(rawToken, newPassword)))
                .andReturn().getResponse().getStatus();
    }
}
