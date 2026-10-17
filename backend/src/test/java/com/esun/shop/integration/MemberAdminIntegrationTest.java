package com.esun.shop.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4.#3 (M0), real MySQL: admin role/status changes revoke the target's outstanding JWTs immediately, the
 * invariants (no self-change, never zero active admins) hold including under concurrency, and every change is audited
 * without secrets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class MemberAdminIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void roleChangeRevokesOldTokenAndReloginWorks() throws Exception {
        String tag = tag();
        String admin = register("m0-admin-" + tag + "@example.com", "ADMIN");
        String email = "m0-buyer-" + tag + "@example.com";
        String oldToken = register(email, "BUYER");
        long id = idOf(email);
        String product = mapper.writeValueAsString(Map.of("productId", "M0-" + tag, "productName", "M0", "price", 10, "quantity", 2));
        mvc.perform(post("/api/products").header("Authorization", bearer(oldToken)).contentType(MediaType.APPLICATION_JSON)
                .content(product)).andExpect(status().isForbidden());

        mvc.perform(patch("/api/admin/members/" + id + "/role").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"SELLER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("SELLER"));

        // the pre-upgrade token is dead everywhere, even for endpoints a BUYER may use
        mvc.perform(post("/api/products").header("Authorization", bearer(oldToken)).contentType(MediaType.APPLICATION_JSON)
                .content(product)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders").header("Authorization", bearer(oldToken))).andExpect(status().isUnauthorized());

        String fresh = login(email);
        mvc.perform(post("/api/products").header("Authorization", bearer(fresh)).contentType(MediaType.APPLICATION_JSON)
                .content(product)).andExpect(status().isOk());
    }

    @Test
    void disableRevokesTokenBlocksLoginAndEnableRestores() throws Exception {
        String tag = tag();
        String admin = register("m0-admin2-" + tag + "@example.com", "ADMIN");
        String email = "m0-victim-" + tag + "@example.com";
        String token = register(email, "BUYER");
        long id = idOf(email);
        mvc.perform(get("/api/orders").header("Authorization", bearer(token))).andExpect(status().isOk());

        mvc.perform(post("/api/admin/members/" + id + "/disable").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISABLED"));
        mvc.perform(get("/api/orders").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials(email, "password123")))
                .andExpect(status().isForbidden());
        // a wrong password must not reveal that the account is disabled
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials(email, "nope-nope-1")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/members/" + id + "/disable").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/admin/members/" + id + "/enable").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mvc.perform(get("/api/orders").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders").header("Authorization", bearer(login(email)))).andExpect(status().isOk());
    }

    @Test
    void tokenWithoutVersionClaimIsRejected() throws Exception {
        String tag = tag();
        String email = "m0-legacy-" + tag + "@example.com";
        register(email, "BUYER");
        String legacy = io.jsonwebtoken.Jwts.builder().subject(email).claim("role", "BUYER")
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
        mvc.perform(get("/api/orders").header("Authorization", bearer(legacy))).andExpect(status().isUnauthorized());
    }

    @Test
    void invariantsAndAuthorization() throws Exception {
        String tag = tag();
        String adminEmail = "m0-admin3-" + tag + "@example.com";
        String admin = register(adminEmail, "ADMIN");
        String buyerEmail = "m0-b3-" + tag + "@example.com";
        String buyer = register(buyerEmail, "BUYER");
        long adminId = idOf(adminEmail);
        long buyerId = idOf(buyerEmail);

        mvc.perform(get("/api/admin/members").header("Authorization", bearer(buyer))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/members/" + adminId + "/role").header("Authorization", bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"BUYER\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/members/" + buyerId + "/disable").header("Authorization", bearer(buyer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/members")).andExpect(status().isUnauthorized());

        mvc.perform(patch("/api/admin/members/" + adminId + "/role").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"BUYER\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/members/" + adminId + "/disable").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/admin/members/" + buyerId + "/role").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"BUYER\"}")).andExpect(status().isConflict());
        mvc.perform(patch("/api/admin/members/" + buyerId + "/role").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ROOT\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/members/99999999/disable").header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());

        // search: prefix + role filter + paging; LIKE metacharacters are literal
        mvc.perform(get("/api/admin/members").param("q", "m0-b3-" + tag).param("role", "BUYER")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].email").value(buyerEmail))
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());
        mvc.perform(get("/api/admin/members").param("q", "%").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/admin/members").param("size", "101").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auditRowsAreWrittenWithoutSecrets() throws Exception {
        String tag = tag();
        String adminEmail = "m0-admin4-" + tag + "@example.com";
        String admin = register(adminEmail, "ADMIN");
        String email = "m0-b4-" + tag + "@example.com";
        register(email, "BUYER");
        long id = idOf(email);
        mvc.perform(patch("/api/admin/members/" + id + "/role").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"SELLER\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/admin/members/" + id + "/disable").header("Authorization", bearer(admin))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/members/" + id + "/enable").header("Authorization", bearer(admin))).andExpect(status().isOk());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action, actor, actor_role, target_type, before_state, after_state FROM audit_log "
                        + "WHERE target_type = 'MEMBER' AND target_id = ? ORDER BY id", String.valueOf(id));
        assertThat(rows).extracting(r -> r.get("action"))
                .containsExactly("MEMBER_ROLE_CHANGE", "MEMBER_DISABLE", "MEMBER_ENABLE");
        for (Map<String, Object> row : rows) {
            assertThat(row.get("actor")).isEqualTo(adminEmail);
            assertThat(row.get("actor_role")).isEqualTo("ADMIN");
            assertThat(String.valueOf(row.get("before_state")) + row.get("after_state"))
                    .doesNotContain("password").doesNotContain("$2a$");
        }
        JsonNode first = mapper.readTree(String.valueOf(rows.get(0).get("after_state")));
        assertThat(first.path("role").asText()).isEqualTo("SELLER");
    }

    @Test
    void concurrentMutualDemotionNeverLeavesZeroActiveAdmins() throws Exception {
        // other tests leave ACTIVE admins behind; park them so the count below is exactly the two under test
        jdbc.update("UPDATE member SET role = 'BUYER' WHERE role = 'ADMIN'");
        String tag = tag();
        String aEmail = "m0-race-a-" + tag + "@example.com";
        String bEmail = "m0-race-b-" + tag + "@example.com";
        String a = register(aEmail, "ADMIN");
        String b = register(bEmail, "ADMIN");
        long aId = idOf(aEmail);
        long bId = idOf(bEmail);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> aDemotesB = pool.submit(() -> {
                barrier.await();
                return mvc.perform(patch("/api/admin/members/" + bId + "/role").header("Authorization", bearer(a))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"BUYER\"}"))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> bDemotesA = pool.submit(() -> {
                barrier.await();
                return mvc.perform(patch("/api/admin/members/" + aId + "/role").header("Authorization", bearer(b))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"BUYER\"}"))
                        .andReturn().getResponse().getStatus();
            });
            List<Integer> statuses = List.of(aDemotesB.get(), bDemotesA.get());
            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
            assertThat(statuses).filteredOn(s -> s != 200).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE role = 'ADMIN' AND status = 'ACTIVE'", Integer.class))
                .isEqualTo(1);
    }

    @org.springframework.beans.factory.annotation.Value("${jwt.secret}")
    String jwtSecret;

    private String register(String email, String role) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email, "password123"))).andExpect(status().isOk());
        jdbc.update("UPDATE member SET role = ? WHERE email = ?", role, email);
        return login(email);
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, "password123")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("token").asText();
    }

    private String credentials(String email, String password) throws Exception {
        return mapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    private long idOf(String email) {
        return jdbc.queryForObject("SELECT id FROM member WHERE email = ?", Long.class, email);
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
