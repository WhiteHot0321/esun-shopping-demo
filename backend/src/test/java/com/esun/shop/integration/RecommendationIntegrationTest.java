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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-MySQL coverage of the read-only recommendation endpoints. Orders are seeded straight into the tables (the
 * feature only reads them); product ids are tag-scoped so this class does not depend on other tests' data, and the
 * shared-database "popular"/"new arrival" tiers are only asserted structurally.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class RecommendationIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    /** Anchor A, B (support 3), C and H (support 2, tie -> id order), D (only via a cancelled order), G (support 1). */
    private record Catalog(String tag, String a, String b, String c, String d, String e, String f, String g, String h) { }

    private Catalog seedCatalog() {
        String tag = tag();
        Catalog k = new Catalog(tag, id(tag, "A"), id(tag, "B"), id(tag, "C"), id(tag, "D"), id(tag, "E"),
                id(tag, "F"), id(tag, "G"), id(tag, "H"));
        for (String id : List.of(k.a, k.b, k.c, k.d, k.g, k.h)) product(id, 5, false);
        product(k.e, 0, false);   // sold out
        product(k.f, 5, true);    // soft-deleted
        order(tag + "-1", "u1-" + tag + "@example.com", "CREATED", k.a, k.b, k.c, k.e, k.f, k.g, k.h);
        order(tag + "-2", "u2-" + tag + "@example.com", "CONFIRMED", k.a, k.b, k.c, k.e, k.f, k.h);
        order(tag + "-3", "u3-" + tag + "@example.com", "CONFIRMED", k.a, k.b, k.d);
        order(tag + "-4", "u3-" + tag + "@example.com", "CANCELLED", k.a, k.d);
        return k;
    }

    @Test
    void productListRanksLiveCoPurchasesByOrderCountAndIsPublic() throws Exception {
        Catalog k = seedCatalog();

        // No token: the per-product list is public. Tie between C and H (2 orders each) breaks by product id.
        JsonNode items = call("/api/products/" + k.a + "/recommendations?limit=3", null, 200);
        assertThat(ids(items)).containsExactly(k.b, k.c, k.h);
        assertThat(items).allSatisfy(item -> assertThat(item.path("reason").asText()).isEqualTo("CO_PURCHASE"));
        assertThat(items.get(0).path("score").asLong()).isEqualTo(3);
        assertThat(items.get(1).path("score").asLong()).isEqualTo(2);
        // The product payload is the catalogue shape (price, stock, images, rating), not just an id.
        assertThat(items.get(0).path("product").path("productName").asText()).isNotBlank();
        assertThat(items.get(0).path("product").has("imageUrls")).isTrue();

        // Excluded from the co-purchase tier: the anchor, sold out (E), soft-deleted (F), support below the minimum (G),
        // and D whose second order was cancelled (support 1, not 2).
        JsonNode wide = call("/api/products/" + k.a + "/recommendations?limit=20", null, 200);
        List<String> coPurchased = new ArrayList<>();
        for (JsonNode item : wide) if ("CO_PURCHASE".equals(item.path("reason").asText())) {
            coPurchased.add(item.path("product").path("productId").asText());
        }
        assertThat(coPurchased).containsExactly(k.b, k.c, k.h);
        assertThat(ids(wide)).doesNotContain(k.a, k.e, k.f);

        // Tiers never interleave: CO_PURCHASE, then POPULAR, then NEW_ARRIVAL, without duplicates.
        assertThat(tierRanks(wide)).isSorted();
        assertThat(ids(wide)).doesNotHaveDuplicates();
        assertThat(ids(wide).size()).isLessThanOrEqualTo(20);
    }

    @Test
    void cancellingOrdersRemovesTheirSignalAndProductChangesAreReflected() throws Exception {
        Catalog k = seedCatalog();
        jdbc.update("UPDATE shop_order SET order_status = 'CANCELLED' WHERE order_id IN (?, ?)", k.tag + "-1", k.tag + "-2");
        // Only order 3 is left alive for the A anchor: every pair has support <= 1, below the minimum.
        JsonNode items = call("/api/products/" + k.a + "/recommendations?limit=20", null, 200);
        for (JsonNode item : items) assertThat(item.path("reason").asText()).isNotEqualTo("CO_PURCHASE");

        jdbc.update("UPDATE shop_order SET order_status = 'CREATED' WHERE order_id IN (?, ?)", k.tag + "-1", k.tag + "-2");
        jdbc.update("UPDATE product SET quantity = 0 WHERE product_id = ?", k.b);
        jdbc.update("UPDATE product SET deleted_at = NOW() WHERE product_id = ?", k.c);
        assertThat(ids(call("/api/products/" + k.a + "/recommendations?limit=1", null, 200)))
                .containsExactly(k.h);
    }

    @Test
    void anchorWithoutHistoryFallsBackToPopularThenNewArrivalsAndNeverToTheAnchor() throws Exception {
        String tag = tag();
        String lonely = id(tag, "Z");
        product(lonely, 3, false);

        JsonNode items = call("/api/products/" + lonely + "/recommendations?limit=20", null, 200);
        assertThat(items.size()).isPositive();
        assertThat(ids(items)).doesNotContain(lonely);
        for (JsonNode item : items) assertThat(item.path("reason").asText()).isNotEqualTo("CO_PURCHASE");
        assertThat(tierRanks(items)).isSorted();
        // The newest sellable product (the anchor is excluded) is available as filler even with no popular products.
        assertThat(items.get(items.size() - 1).path("reason").asText()).isIn("POPULAR", "NEW_ARRIVAL");
    }

    @Test
    void invalidRequestsAreRejectedWithoutTouchingData() throws Exception {
        Catalog k = seedCatalog();
        call("/api/products/NOPE-" + k.tag + "/recommendations", null, 404);
        jdbc.update("UPDATE product SET deleted_at = NOW() WHERE product_id = ?", k.h);
        call("/api/products/" + k.h + "/recommendations", null, 404);
        call("/api/products/" + k.a + "/recommendations?limit=0", null, 400);
        call("/api/products/" + k.a + "/recommendations?limit=21", null, 400);
        call("/api/products/" + k.a + "/recommendations?limit=abc", null, 400);
    }

    @Test
    void personalisedListNeedsALoginAndUsesOnlyTheVerifiedIdentity() throws Exception {
        Catalog k = seedCatalog();
        call("/api/recommendations", null, 401);

        // The buyer owns A (live order) and has H in the cart; a cancelled purchase of B must not count as owned.
        String email = "bob-" + k.tag + "@example.com";
        String token = buyerToken(email);
        order(k.tag + "-b1", email, "CREATED", k.a);
        order(k.tag + "-b2", email, "CANCELLED", k.b);
        jdbc.update("INSERT INTO shopping_cart(member_id, product_id, quantity) SELECT id, ?, 1 FROM member WHERE email = ?",
                k.h, email);

        JsonNode items = call("/api/recommendations?limit=20", token, 200);
        List<String> coPurchased = new ArrayList<>();
        for (JsonNode item : items) if ("CO_PURCHASE".equals(item.path("reason").asText())) {
            coPurchased.add(item.path("product").path("productId").asText());
        }
        // Learned from the other buyers' baskets around A: B (3) and C (2); H is carted, A is owned.
        assertThat(coPurchased).containsExactly(k.b, k.c);
        assertThat(ids(items)).doesNotContain(k.a, k.h, k.e, k.f);

        // A caller cannot ask for someone else's list: the identity is the token, a member id parameter is ignored.
        JsonNode spoof = call("/api/recommendations?limit=20&memberId=u1-" + k.tag + "@example.com", token, 200);
        assertThat(ids(spoof)).isEqualTo(ids(items));

        // A member with no history gets no co-purchase tier at all, only the shop-wide fallbacks.
        String newcomer = buyerToken("new-" + k.tag + "@example.com");
        for (JsonNode item : call("/api/recommendations?limit=20", newcomer, 200)) {
            assertThat(item.path("reason").asText()).isNotEqualTo("CO_PURCHASE");
        }
        call("/api/recommendations?limit=0", token, 400);
    }

    @Test
    void migrationIsRepeatableAndTheCoPurchaseIndexExistsOnce() throws Exception {
        for (int run = 0; run < 2; run++) {
            try (java.sql.Connection connection = dataSource.getConnection()) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.FileSystemResource("DB/15_recommendation.sql"));
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'order_detail' "
                + "AND index_name = 'idx_order_detail_product_order'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema = DATABASE() "
                + "AND table_name = 'order_detail' AND index_name = 'idx_order_detail_product_order' "
                + "ORDER BY seq_in_index", String.class)).containsExactly("product_id", "order_id");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private JsonNode call(String url, String token, int expectedStatus) throws Exception {
        var request = get(url);
        if (token != null) request = request.header("Authorization", "Bearer " + token);
        String body = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse()
                .getContentAsString();
        return body.isEmpty() ? mapper.createArrayNode() : mapper.readTree(body).path("data");
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : items) ids.add(item.path("product").path("productId").asText());
        return ids;
    }

    private static List<Integer> tierRanks(JsonNode items) {
        List<Integer> ranks = new ArrayList<>();
        for (JsonNode item : items) {
            ranks.add(List.of("CO_PURCHASE", "POPULAR", "NEW_ARRIVAL").indexOf(item.path("reason").asText()));
        }
        return ranks;
    }

    private void product(String id, int quantity, boolean deleted) {
        jdbc.update("INSERT INTO product(product_id, product_name, price, quantity, creator_id, deleted_at) "
                + "VALUES (?, ?, 100, ?, 'seller@example.com', " + (deleted ? "NOW()" : "NULL") + ")",
                id, "推薦測試商品 " + id, quantity);
    }

    private void order(String orderId, String memberId, String status, String... productIds) {
        jdbc.update("INSERT INTO shop_order(order_id, member_id, price, pay_status, order_status) VALUES (?, ?, 100, 0, ?)",
                orderId, memberId, status);
        for (String productId : productIds) {
            jdbc.update("INSERT INTO order_detail(order_id, product_id, quantity, unit_price, item_price) "
                    + "VALUES (?, ?, 1, 100, 100)", orderId, productId);
        }
    }

    private String buyerToken(String email) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk());
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("token").asText();
    }

    private static String id(String tag, String suffix) {
        return "R" + tag + suffix;
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
