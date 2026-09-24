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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-MySQL coverage of the order status flow: buyer history/tracking/cancellation, seller/admin fulfilment
 * transitions, ownership scoping, stock restoration on cancel and cancel-vs-cancel concurrency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class OrderStatusIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Autowired javax.sql.DataSource dataSource;

    @Test
    void migrationIsRepeatableAndBackfillsHistoryForLegacyOrders() throws Exception {
        String legacyId = "LEGACY-" + tag();
        // A pre-feature order: inserted without any history row, exactly as old code would have.
        jdbc.update("INSERT INTO shop_order(order_id, member_id, price, pay_status) VALUES (?, 'legacy@example.com', 10, 0)",
                legacyId);
        assertThat(historyCount(legacyId)).isZero();

        for (int run = 0; run < 2; run++) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(dataSource.getConnection(),
                    new org.springframework.core.io.FileSystemResource("DB/11_order_status.sql"));
            assertThat(historyCount(legacyId)).as("run %d", run + 1).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT order_status FROM shop_order WHERE order_id = ?", String.class, legacyId))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT to_status FROM order_status_history WHERE order_id = ?", String.class, legacyId))
                .isEqualTo("CREATED");
    }

    private int historyCount(String orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", Integer.class, orderId);
    }

    @Test
    void buyerTracksOwnOrdersAndCannotSeeOthers() throws Exception {
        String tag = tag();
        String seller = sellerToken("os-seller-" + tag + "@example.com");
        String buyer = buyerToken("os-buyer-" + tag + "@example.com");
        String stranger = buyerToken("os-stranger-" + tag + "@example.com");
        createProduct(seller, "A-" + tag, 100, 10);
        String orderId = placeOrder(buyer, "A-" + tag, 2);

        mvc.perform(get("/api/orders").header("Authorization", bearer(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.orders[0].orderId").value(orderId))
                .andExpect(jsonPath("$.data.orders[0].status").value("CREATED"))
                .andExpect(jsonPath("$.data.orders[0].price").value(200))
                .andExpect(jsonPath("$.data.orders[0].items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.orders[0].receiverName").value("王小明"))
                .andExpect(jsonPath("$.data.orders[0].timeline.length()").value(1))
                .andExpect(jsonPath("$.data.orders[0].timeline[0].toStatus").value("CREATED"))
                .andExpect(jsonPath("$.data.orders[0].allowedActions[0]").value("CANCELLED"));
        mvc.perform(get("/api/orders").header("Authorization", bearer(buyer)).param("status", "SHIPPED"))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/orders").header("Authorization", bearer(buyer)).param("status", "bogus"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").header("Authorization", bearer(buyer)).param("size", "0"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/orders/{id}", orderId).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/orders").header("Authorization", bearer(stranger)))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        assertThat(orderStatus(orderId)).isEqualTo("CREATED");
    }

    @Test
    void sellerWalksTheHappyPathAndInvalidTransitionsAreRejected() throws Exception {
        String tag = tag();
        String seller = sellerToken("walk-seller-" + tag + "@example.com");
        String otherSeller = sellerToken("walk-other-" + tag + "@example.com");
        String buyer = buyerToken("walk-buyer-" + tag + "@example.com");
        createProduct(seller, "W-" + tag, 50, 10);
        String orderId = placeOrder(buyer, "W-" + tag, 1);

        // Buyers hold no fulfilment rights; sellers with no line in the order cannot even see it.
        setStatus(buyer, orderId, "CONFIRMED").andExpect(status().isForbidden());
        setStatus(otherSeller, orderId, "CONFIRMED").andExpect(status().isNotFound());
        mvc.perform(get("/api/seller/orders/{id}", orderId).header("Authorization", bearer(otherSeller)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/seller/orders").header("Authorization", bearer(otherSeller)))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/seller/orders").header("Authorization", bearer(buyer))).andExpect(status().isForbidden());

        // Skipping steps is a state-machine violation, not a silent success.
        setStatus(seller, orderId, "SHIPPED").andExpect(status().isConflict());
        setStatus(seller, orderId, "DELIVERED").andExpect(status().isConflict());
        setStatus(seller, orderId, "CREATED").andExpect(status().isConflict());
        mvc.perform(post("/api/seller/orders/{id}/status", orderId).header("Authorization", bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/seller/orders/{id}/status", orderId).header("Authorization", bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(orderStatus(orderId)).isEqualTo("CREATED");

        setStatus(seller, orderId, "CONFIRMED").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.memberId").doesNotExist());
        setStatus(seller, orderId, "SHIPPED").andExpect(status().isOk());
        // Once shipped the buyer can no longer cancel, and stock stays deducted.
        mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", bearer(buyer)))
                .andExpect(status().isConflict());
        setStatus(seller, orderId, "CANCELLED").andExpect(status().isConflict());
        setStatus(seller, orderId, "DELIVERED").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowedActions.length()").value(0));
        setStatus(seller, orderId, "CANCELLED").andExpect(status().isConflict());

        mvc.perform(get("/api/orders/{id}", orderId).header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.timeline.length()").value(4))
                .andExpect(jsonPath("$.data.timeline[1].fromStatus").value("CREATED"))
                .andExpect(jsonPath("$.data.timeline[1].toStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.timeline[1].actorRole").value("SELLER"))
                .andExpect(jsonPath("$.data.timeline[3].toStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.data.allowedActions.length()").value(0));
        assertThat(quantity("W-" + tag)).isEqualTo(9);
        mvc.perform(get("/api/seller/orders").header("Authorization", bearer(seller)).param("status", "delivered"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.orders[0].orderId").value(orderId));
    }

    @Test
    void cancellationReturnsStockOnceAndBothSidesMayCancelBeforeShipping() throws Exception {
        String tag = tag();
        String seller = sellerToken("cancel-seller-" + tag + "@example.com");
        String buyer = buyerToken("cancel-buyer-" + tag + "@example.com");
        createProduct(seller, "C1-" + tag, 10, 10);
        createProduct(seller, "C2-" + tag, 20, 5);

        String byBuyer = placeOrder(buyer, "C1-" + tag, 3);
        assertThat(quantity("C1-" + tag)).isEqualTo(7);
        mvc.perform(post("/api/orders/{id}/cancel", byBuyer).header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(quantity("C1-" + tag)).isEqualTo(10);
        mvc.perform(post("/api/orders/{id}/cancel", byBuyer).header("Authorization", bearer(buyer)))
                .andExpect(status().isConflict());
        assertThat(quantity("C1-" + tag)).isEqualTo(10);

        String bySeller = placeOrder(buyer, "C2-" + tag, 4);
        setStatus(seller, bySeller, "CONFIRMED").andExpect(status().isOk());
        assertThat(quantity("C2-" + tag)).isEqualTo(1);
        // Buyer may still cancel a CONFIRMED order; a seller cancellation is equally allowed pre-ship.
        setStatus(seller, bySeller, "CANCELLED").andExpect(status().isOk());
        assertThat(quantity("C2-" + tag)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", Integer.class, bySeller))
                .isEqualTo(3);
    }

    @Test
    void concurrentCancellationsRestoreStockExactlyOnce() throws Exception {
        String tag = tag();
        String seller = sellerToken("race-seller-" + tag + "@example.com");
        String buyer = buyerToken("race-buyer-" + tag + "@example.com");
        createProduct(seller, "R-" + tag, 10, 10);
        String orderId = placeOrder(buyer, "R-" + tag, 4);

        int callers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                boolean bySeller = i % 2 == 0;
                tasks.add(() -> bySeller
                        ? setStatus(seller, orderId, "CANCELLED").andReturn().getResponse().getStatus()
                        : mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", bearer(buyer)))
                                .andReturn().getResponse().getStatus());
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(tasks)) codes.add(f.get());
            assertThat(codes).filteredOn(c -> c == 200).hasSize(1);
            assertThat(codes).filteredOn(c -> c == 409).hasSize(callers - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(quantity("R-" + tag)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'CANCELLED'",
                Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void mixedSellerOrdersAreScopedPerSellerAndOnlyAdminCanMoveThem() throws Exception {
        String tag = tag();
        String sellerA = sellerToken("mix-a-" + tag + "@example.com");
        String sellerB = sellerToken("mix-b-" + tag + "@example.com");
        String admin = adminToken("mix-admin-" + tag + "@example.com");
        String buyer = buyerToken("mix-buyer-" + tag + "@example.com");
        createProduct(sellerA, "MA-" + tag, 10, 10);
        createProduct(sellerB, "MB-" + tag, 30, 10);
        String orderId = placeOrderItems(buyer, Map.of("productId", "MA-" + tag, "quantity", 1),
                Map.of("productId", "MB-" + tag, "quantity", 2));

        // Each seller sees only their own lines/subtotal and never the buyer identity.
        mvc.perform(get("/api/seller/orders/{id}", orderId).header("Authorization", bearer(sellerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].productId").value("MA-" + tag))
                .andExpect(jsonPath("$.data.price").value(10))
                .andExpect(jsonPath("$.data.memberId").doesNotExist())
                .andExpect(jsonPath("$.data.allowedActions.length()").value(0));
        mvc.perform(get("/api/seller/orders/{id}", orderId).header("Authorization", bearer(sellerB)))
                .andExpect(jsonPath("$.data.items[0].productId").value("MB-" + tag))
                .andExpect(jsonPath("$.data.price").value(60));
        // Order status is per order, so a single seller cannot move an order that contains someone else's goods.
        setStatus(sellerA, orderId, "CONFIRMED").andExpect(status().isForbidden());
        assertThat(orderStatus(orderId)).isEqualTo("CREATED");

        mvc.perform(get("/api/admin/orders/{id}", orderId).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.price").value(70))
                .andExpect(jsonPath("$.data.allowedActions.length()").value(2));
        mvc.perform(post("/api/admin/orders/{id}/status", orderId).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        // Admin cancellation returns every line's stock.
        mvc.perform(post("/api/admin/orders/{id}/status", orderId).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());
        assertThat(quantity("MA-" + tag)).isEqualTo(10);
        assertThat(quantity("MB-" + tag)).isEqualTo(10);
    }

    // ---- helpers ----

    private org.springframework.test.web.servlet.ResultActions setStatus(String token, String orderId, String target)
            throws Exception {
        return mvc.perform(post("/api/seller/orders/{id}/status", orderId).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + target + "\"}"));
    }

    private String orderStatus(String orderId) {
        return jdbc.queryForObject("SELECT order_status FROM shop_order WHERE order_id = ?", String.class, orderId);
    }

    private int quantity(String productId) {
        return jdbc.queryForObject("SELECT quantity FROM product WHERE product_id = ?", Integer.class, productId);
    }

    private void createProduct(String sellerToken, String id, int price, int quantity) throws Exception {
        mvc.perform(post("/api/admin/products").header("Authorization", bearer(sellerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("productId", id, "productName", "Item " + id,
                                "price", price, "quantity", quantity))))
                .andExpect(status().isOk());
    }

    private String placeOrder(String buyerToken, String productId, int quantity) throws Exception {
        return placeOrderItems(buyerToken, Map.of("productId", productId, "quantity", quantity));
    }

    @SafeVarargs
    private String placeOrderItems(String buyerToken, Map<String, Object>... items) throws Exception {
        // Idempotent: the first call creates the default address, later calls simply add another one.
        mvc.perform(post("/api/member/addresses").header("Authorization", bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("label", "住家", "receiverName", "王小明",
                                "phone", "0912-345-678", "postalCode", "100", "address", "台北市中正區測試路 1 號",
                                "isDefault", true))))
                .andExpect(status().isOk());
        String response = mvc.perform(post("/api/orders").header("Authorization", bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("requestId", UUID.randomUUID().toString(),
                                "payStatus", "PENDING", "items", List.of(items)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("orderId").asText();
    }

    private String sellerToken(String email) throws Exception {
        return tokenWithRole(email, "SELLER");
    }

    private String adminToken(String email) throws Exception {
        return tokenWithRole(email, "ADMIN");
    }

    private String buyerToken(String email) throws Exception {
        register(email);
        return login(email);
    }

    private String tokenWithRole(String email, String role) throws Exception {
        register(email);
        jdbc.update("UPDATE member SET role = ? WHERE email = ?", role, email);
        return login(email);
    }

    private void register(String email) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk());
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(body);
        return node.path("data").path("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
