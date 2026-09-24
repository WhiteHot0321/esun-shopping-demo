package com.esun.shop.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-MySQL coverage of the operation audit log: every privileged write leaves exactly one attributable row with
 * accurate before/after snapshots, rows commit/roll back atomically with the change, only ADMIN can read them, and
 * concurrent writers cannot corrupt the before/after chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AuditLogIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    /** Other integration tests wipe the member table; reviews written here would violate that foreign key. */
    @AfterEach
    void cleanUpReviews() {
        jdbc.update("DELETE FROM product_review WHERE product_id LIKE 'OR-%'");
    }

    @Test
    void timestampsAreWrittenInApplicationTime() throws Exception {
        String tag = tag();
        String token = tokenWithRole("time-seller-" + tag + "@example.com", "SELLER");
        String admin = tokenWithRole("time-admin-" + tag + "@example.com", "ADMIN");
        java.time.LocalDateTime before = java.time.LocalDateTime.now().minusSeconds(2);
        createProduct(token, "TM-" + tag, 10, 1).andExpect(status().isOk());
        java.time.LocalDateTime after = java.time.LocalDateTime.now().plusSeconds(2);

        String body = mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin))
                .param("targetId", "TM-" + tag)).andReturn().getResponse().getContentAsString();
        java.time.LocalDateTime createdAt = java.time.LocalDateTime.parse(
                mapper.readTree(body).path("data").path("entries").get(0).path("createdAt").asText());
        assertThat(createdAt).isBetween(before, after);
        // A window around "now" finds the row; the same window shifted by 8 hours (the UTC/Taipei skew) does not.
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin)).param("targetId", "TM-" + tag)
                        .param("from", before.toString()).param("to", after.toString()))
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin)).param("targetId", "TM-" + tag)
                        .param("from", before.minusHours(8).toString()).param("to", after.minusHours(8).toString()))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void migrationIsRepeatableAndKeepsExistingRows() throws Exception {
        String actor = "mig-" + tag() + "@example.com";
        jdbc.update("INSERT INTO audit_log(actor, actor_role, action, target_type, target_id) "
                + "VALUES (?, 'ADMIN', 'PRODUCT_CREATE', 'PRODUCT', 'MIG-1')", actor);
        for (int run = 0; run < 2; run++) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(dataSource.getConnection(),
                    new org.springframework.core.io.FileSystemResource("DB/12_audit_log.sql"));
            assertThat(auditCount("actor", actor)).as("run %d", run + 1).isEqualTo(1);
        }
    }

    @Test
    void productLifecycleIsAuditedWithAccurateBeforeAndAfter() throws Exception {
        String tag = tag();
        String seller = "life-seller-" + tag + "@example.com";
        String token = tokenWithRole(seller, "SELLER");
        String id = "AU-" + tag;

        createProduct(token, id, 100, 10).andExpect(status().isOk());
        mvc.perform(put("/api/admin/products/{id}", id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("productName", "Renamed", "price", 150))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/products/{id}/restock", id).param("amount", "5")
                        .header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(multipart("/api/seller/products/" + id + "/images")
                        .file(new MockMultipartFile("images", "a.png", "image/png", PNG))
                        .header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/products/{id}", id).header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT actor, actor_role, action, target_type, before_state, after_state FROM audit_log "
                        + "WHERE target_id = ? ORDER BY id", id);
        assertThat(rows).extracting(r -> r.get("action")).containsExactly(
                "PRODUCT_CREATE", "PRODUCT_UPDATE", "PRODUCT_RESTOCK", "PRODUCT_IMAGE_UPLOAD", "PRODUCT_DELETE");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("actor")).isEqualTo(seller);
            assertThat(r.get("actor_role")).isEqualTo("SELLER");
            assertThat(r.get("target_type")).isEqualTo("PRODUCT");
        });

        JsonNode create = json(rows.get(0).get("after_state"));
        assertThat(rows.get(0).get("before_state")).isNull();
        assertThat(create.path("price").decimalValue()).isEqualByComparingTo("100");
        assertThat(create.path("quantity").asInt()).isEqualTo(10);

        JsonNode updateBefore = json(rows.get(1).get("before_state"));
        JsonNode updateAfter = json(rows.get(1).get("after_state"));
        assertThat(updateBefore.path("productName").asText()).isEqualTo("Item " + id);
        assertThat(updateBefore.path("price").decimalValue()).isEqualByComparingTo("100");
        assertThat(updateAfter.path("productName").asText()).isEqualTo("Renamed");
        assertThat(updateAfter.path("price").decimalValue()).isEqualByComparingTo("150");

        JsonNode restockBefore = json(rows.get(2).get("before_state"));
        JsonNode restockAfter = json(rows.get(2).get("after_state"));
        assertThat(restockBefore.path("quantity").asInt()).isEqualTo(10);
        assertThat(restockAfter.path("quantity").asInt()).isEqualTo(15);
        assertThat(restockAfter.path("restockAmount").asInt()).isEqualTo(5);

        assertThat(json(rows.get(3).get("after_state")).path("addedImageUrls").size()).isEqualTo(1);

        assertThat(json(rows.get(4).get("before_state")).path("deleted").asBoolean()).isFalse();
        assertThat(json(rows.get(4).get("after_state")).path("deleted").asBoolean()).isTrue();
        // No secrets ever appear in a snapshot.
        assertThat(rows.toString()).doesNotContain("password");
    }

    @Test
    void bulkOperationsWriteOneRowPerProductAndRollBackTogether() throws Exception {
        String tag = tag();
        String seller = "bulk-seller-" + tag + "@example.com";
        String token = tokenWithRole(seller, "SELLER");
        String a = "BA-" + tag;
        String b = "BB-" + tag;
        createProduct(token, a, 10, 1).andExpect(status().isOk());
        createProduct(token, b, 20, 2).andExpect(status().isOk());

        bulk(token, Map.of("action", "RESTOCK", "amount", 7, "productIds", List.of(a, b))).andExpect(status().isOk());
        assertThat(auditCount("action", "PRODUCT_RESTOCK", a)).isEqualTo(1);
        JsonNode bAfter = json(jdbc.queryForObject("SELECT after_state FROM audit_log WHERE target_id = ? "
                + "AND action = 'PRODUCT_RESTOCK'", String.class, b));
        JsonNode bBefore = json(jdbc.queryForObject("SELECT before_state FROM audit_log WHERE target_id = ? "
                + "AND action = 'PRODUCT_RESTOCK'", String.class, b));
        assertThat(bBefore.path("quantity").asInt()).isEqualTo(2);
        assertThat(bAfter.path("quantity").asInt()).isEqualTo(9);

        // One id the seller does not own -> whole batch rejected, nothing audited for the valid product either.
        long before = totalRows();
        bulk(token, Map.of("action", "DELETE", "productIds", List.of(a, "NOT-MINE-" + tag))).andExpect(status().isForbidden());
        assertThat(totalRows()).isEqualTo(before);

        bulk(token, Map.of("action", "DELETE", "productIds", List.of(a, b))).andExpect(status().isOk());
        assertThat(auditCount("action", "PRODUCT_DELETE", a)).isEqualTo(1);
        assertThat(auditCount("action", "PRODUCT_DELETE", b)).isEqualTo(1);
    }

    @Test
    void rejectedOperationsLeaveNoAuditRow() throws Exception {
        String tag = tag();
        String owner = tokenWithRole("rej-owner-" + tag + "@example.com", "SELLER");
        String intruder = tokenWithRole("rej-other-" + tag + "@example.com", "SELLER");
        String id = "RJ-" + tag;
        createProduct(owner, id, 10, 3).andExpect(status().isOk());
        long baseline = auditCount("target_id", id);

        mvc.perform(post("/api/admin/products/{id}/restock", id).param("amount", "2")
                .header("Authorization", bearer(intruder))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/products/{id}", id).header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/products/{id}/restock", id).param("amount", "0")
                .header("Authorization", bearer(owner))).andExpect(status().isBadRequest());
        createProduct(owner, id, 10, 3).andExpect(status().isConflict());
        assertThat(auditCount("target_id", id)).isEqualTo(baseline);
    }

    @Test
    void auditRowAndBusinessChangeRollBackTogether() throws Exception {
        String tag = tag();
        String token = tokenWithRole("atomic-seller-" + tag + "@example.com", "SELLER");
        String id = "AT-" + tag;
        createProduct(token, id, 10, 4).andExpect(status().isOk());

        // Make the audit insert fail, then prove the restock it describes did not survive on its own.
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT chk_audit_fail CHECK (action <> 'PRODUCT_RESTOCK')");
        try {
            mvc.perform(post("/api/admin/products/{id}/restock", id).param("amount", "3")
                    .header("Authorization", bearer(token))).andExpect(status().is5xxServerError());
        } finally {
            jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT chk_audit_fail");
        }
        assertThat(jdbc.queryForObject("SELECT quantity FROM product WHERE product_id = ?", Integer.class, id)).isEqualTo(4);
        assertThat(auditCount("action", "PRODUCT_RESTOCK", id)).isZero();
    }

    @Test
    void orderStatusChangesAndReviewModerationAreAudited() throws Exception {
        String tag = tag();
        String sellerEmail = "ord-seller-" + tag + "@example.com";
        String buyerEmail = "ord-buyer-" + tag + "@example.com";
        String seller = tokenWithRole(sellerEmail, "SELLER");
        String buyer = tokenWithRole(buyerEmail, "BUYER");
        String id = "OR-" + tag;
        createProduct(seller, id, 50, 10).andExpect(status().isOk());
        String orderId = placeOrder(buyer, id, 2);

        mvc.perform(post("/api/seller/orders/{id}/status", orderId).header("Authorization", bearer(seller))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONFIRMED\"}")).andExpect(status().isOk());
        // Illegal transition is rejected and must not be audited.
        mvc.perform(post("/api/seller/orders/{id}/status", orderId).header("Authorization", bearer(seller))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELIVERED\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", bearer(buyer))).andExpect(status().isOk());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT actor, actor_role, before_state, after_state FROM audit_log WHERE action = 'ORDER_STATUS_CHANGE' "
                        + "AND target_type = 'ORDER' AND target_id = ? ORDER BY id", orderId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("actor")).isEqualTo(sellerEmail);
        assertThat(rows.get(0).get("actor_role")).isEqualTo("SELLER");
        assertThat(json(rows.get(0).get("before_state")).path("status").asText()).isEqualTo("CREATED");
        assertThat(json(rows.get(0).get("after_state")).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(rows.get(1).get("actor")).isEqualTo(buyerEmail);
        assertThat(rows.get(1).get("actor_role")).isEqualTo("BUYER");
        assertThat(json(rows.get(1).get("after_state")).path("status").asText()).isEqualTo("CANCELLED");

        // Review moderation: a buyer who bought the product reviews it, the seller hides then restores it.
        String review = mvc.perform(post("/api/products/{id}/reviews", id).header("Authorization", bearer(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("rating", 4, "content", "不錯的商品"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long reviewId = mapper.readTree(review).path("data").path("id").asLong();
        mvc.perform(post("/api/seller/reviews/{id}/hide", reviewId).header("Authorization", bearer(seller)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/seller/reviews/{id}/restore", reviewId).header("Authorization", bearer(seller)))
                .andExpect(status().isOk());
        List<Map<String, Object>> moderation = jdbc.queryForList(
                "SELECT before_state, after_state FROM audit_log WHERE action = 'REVIEW_VISIBILITY_CHANGE' "
                        + "AND target_id = ? ORDER BY id", String.valueOf(reviewId));
        assertThat(moderation).hasSize(2);
        assertThat(json(moderation.get(0).get("before_state")).path("visibility").asText()).isEqualTo("VISIBLE");
        assertThat(json(moderation.get(0).get("after_state")).path("visibility").asText()).isEqualTo("HIDDEN");
        assertThat(json(moderation.get(1).get("after_state")).path("visibility").asText()).isEqualTo("VISIBLE");
    }

    @Test
    void onlyAdminCanReadTheLogAndFiltersWork() throws Exception {
        String tag = tag();
        String sellerEmail = "q-seller-" + tag + "@example.com";
        String seller = tokenWithRole(sellerEmail, "SELLER");
        String admin = tokenWithRole("q-admin-" + tag + "@example.com", "ADMIN");
        String buyer = tokenWithRole("q-buyer-" + tag + "@example.com", "BUYER");
        createProduct(seller, "Q1-" + tag, 10, 1).andExpect(status().isOk());
        createProduct(seller, "Q2-" + tag, 10, 1).andExpect(status().isOk());
        mvc.perform(post("/api/admin/products/{id}/restock", "Q1-" + tag).param("amount", "1")
                .header("Authorization", bearer(seller))).andExpect(status().isOk());

        mvc.perform(get("/api/admin/audit-logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(buyer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(seller))).andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin)).param("actor", sellerEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                // Newest first.
                .andExpect(jsonPath("$.data.entries[0].action").value("PRODUCT_RESTOCK"))
                .andExpect(jsonPath("$.data.entries[0].actorRole").value("SELLER"))
                .andExpect(jsonPath("$.data.entries[0].before.quantity").value(1))
                .andExpect(jsonPath("$.data.entries[0].after.quantity").value(2))
                .andExpect(jsonPath("$.data.entries[2].action").value("PRODUCT_CREATE"))
                .andExpect(jsonPath("$.data.entries[2].before").doesNotExist());
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin))
                        .param("actor", sellerEmail).param("action", "PRODUCT_CREATE").param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.entries.length()").value(1));
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin))
                        .param("targetType", "PRODUCT").param("targetId", "Q2-" + tag))
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin))
                        .param("actor", sellerEmail).param("from", "2999-01-01T00:00:00"))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin))
                        .param("actor", sellerEmail).param("from", "2000-01-01T00:00:00").param("to", "2999-01-01T00:00:00"))
                .andExpect(jsonPath("$.data.total").value(3));

        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin)).param("action", "BOGUS"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin)).param("from", "yesterday"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin))
                        .param("from", "2026-02-01T00:00:00").param("to", "2026-01-01T00:00:00"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin)).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(admin)).param("page", "-1"))
                .andExpect(status().isBadRequest());

        // Append-only surface: the log exposes no mutation endpoints.
        for (var request : List.of(delete("/api/admin/audit-logs"), post("/api/admin/audit-logs"),
                put("/api/admin/audit-logs/1"), delete("/api/admin/audit-logs/1"))) {
            int code = mvc.perform(request.header("Authorization", bearer(admin))).andReturn().getResponse().getStatus();
            assertThat(code).as(request.toString()).isGreaterThanOrEqualTo(400);
        }
    }

    @Test
    void concurrentRestocksKeepTheBeforeAfterChainConsistent() throws Exception {
        String tag = tag();
        String token = tokenWithRole("race-seller-" + tag + "@example.com", "SELLER");
        String id = "RC-" + tag;
        createProduct(token, id, 10, 0).andExpect(status().isOk());

        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                tasks.add(() -> mvc.perform(post("/api/admin/products/{id}/restock", id).param("amount", "5")
                        .header("Authorization", bearer(token))).andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(tasks)) assertThat(f.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT before_state, after_state FROM audit_log "
                + "WHERE target_id = ? AND action = 'PRODUCT_RESTOCK' ORDER BY id", id);
        assertThat(rows).hasSize(callers);
        int expectedBefore = 0;
        for (Map<String, Object> row : rows) {
            // Each audited change starts exactly where the previous one ended: no lost or duplicated snapshots.
            assertThat(json(row.get("before_state")).path("quantity").asInt()).isEqualTo(expectedBefore);
            expectedBefore += 5;
            assertThat(json(row.get("after_state")).path("quantity").asInt()).isEqualTo(expectedBefore);
        }
        assertThat(jdbc.queryForObject("SELECT quantity FROM product WHERE product_id = ?", Integer.class, id))
                .isEqualTo(callers * 5);
    }

    // ---- helpers ----

    private long auditCount(String column, String value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE " + column + " = ?", Long.class, value);
    }

    private long auditCount(String column, String value, String targetId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE " + column + " = ? AND target_id = ?",
                Long.class, value, targetId);
    }

    private long totalRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class);
    }

    private JsonNode json(Object value) throws Exception {
        return mapper.readTree(String.valueOf(value));
    }

    private ResultActions createProduct(String token, String id, int price, int quantity) throws Exception {
        return mvc.perform(post("/api/admin/products").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("productId", id, "productName", "Item " + id,
                        "price", price, "quantity", quantity))));
    }

    private ResultActions bulk(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/seller/products/bulk").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private String placeOrder(String buyerToken, String productId, int quantity) throws Exception {
        mvc.perform(post("/api/member/addresses").header("Authorization", bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("label", "住家", "receiverName", "王小明",
                                "phone", "0912-345-678", "postalCode", "100", "address", "台北市中正區測試路 1 號",
                                "isDefault", true))))
                .andExpect(status().isOk());
        String response = mvc.perform(post("/api/orders").header("Authorization", bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("requestId", UUID.randomUUID().toString(),
                                "payStatus", "PENDING",
                                "items", List.of(Map.of("productId", productId, "quantity", quantity))))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("orderId").asText();
    }

    private String tokenWithRole(String email, String role) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk());
        jdbc.update("UPDATE member SET role = ? WHERE email = ?", role, email);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
