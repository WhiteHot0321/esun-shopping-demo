package com.esun.shop.integration;

import com.esun.shop.repository.OpsDashboardRepository;
import com.esun.shop.service.DashboardWindow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4.#5 (M4), real MySQL: ADMIN-only access, the response shape, and the aggregate SQL against rows placed on and
 * around the Asia/Taipei day boundary. The shared container accumulates rows from other test classes, so the SQL is
 * checked either in a far-future day nothing else writes to or as before/after deltas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class OpsDashboardIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    // Taipei day 2031-05-06 == stored (UTC) [2031-05-05 16:00:00, 2031-05-06 16:00:00)
    private static final DashboardWindow WINDOW = DashboardWindow.today(Instant.parse("2031-05-06T04:00:00Z"), UTC);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired OpsDashboardRepository repository;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payment WHERE order_id LIKE ?", "OPS" + tag + "%");
        jdbc.update("DELETE FROM shop_order WHERE order_id LIKE ?", "OPS" + tag + "%");
        jdbc.update("DELETE FROM product WHERE product_id LIKE ?", "OP" + tag + "%");
    }

    // ---- HTTP: authorization and shape -------------------------------------------------------------------------

    @Test
    void onlyAnAdminMayReadTheDashboard() throws Exception {
        mvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/dashboard").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/dashboard").header("Authorization", bearer(register("BUYER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/dashboard").header("Authorization", bearer(register("SELLER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/dashboard").header("Authorization", bearer(register("ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void responseShapeAndRedisDisabledStateAndNoIdentifiersLeak() throws Exception {
        String sellerEmail = "ops-seller-" + tag + "@example.com";
        insertProduct("OP" + tag + "-leak", 0, false, sellerEmail);
        String admin = register("ADMIN");

        String body = mvc.perform(get("/api/admin/dashboard").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timezone").value("Asia/Taipei"))
                .andExpect(jsonPath("$.data.redis.mode").value("DISABLED"))
                .andExpect(jsonPath("$.data.redis.scope").value("THIS_INSTANCE"))
                .andExpect(jsonPath("$.data.lowStock.threshold").value(5))
                .andExpect(jsonPath("$.data.today.revenue").isNumber())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = mapper.readTree(body).path("data");
        assertThat(data.path("asOf").asText()).endsWith("+08:00");
        assertThat(data.path("today").path("from").asText()).endsWith("T00:00:00+08:00");
        assertThat(data.path("today").path("to").asText()).endsWith("T00:00:00+08:00");
        // aggregates only: the seller's account identifier (creator_id) must never be serialized
        assertThat(body).doesNotContain(sellerEmail).doesNotContain("creator").doesNotContain("member");
    }

    // ---- SQL: window boundaries and exclusions -----------------------------------------------------------------

    @Test
    void todayCountsAndRevenueRespectTheTaipeiDayBoundaryAndExcludeCancelledOrders() {
        // outside, by one second on each side of [from, to)
        order("before", "2031-05-05 15:59:59", 1, "CREATED", "1000.00");
        order("after", "2031-05-06 16:00:00", 1, "CREATED", "2000.00");
        // inside: both edges, and the middle
        order("first", "2031-05-05 16:00:00", 1, "CREATED", "1.25");
        order("last", "2031-05-06 15:59:59", 1, "DELIVERED", "2.50");
        order("paid", "2031-05-06 03:00:00", 1, "CONFIRMED", "20.00");
        order("pending", "2031-05-06 04:00:00", 0, "CREATED", "5.50");
        // cancelled never counts, whether or not it had been paid
        order("cancelledPaid", "2031-05-06 05:00:00", 1, "CANCELLED", "99.00");
        order("cancelledPending", "2031-05-06 06:00:00", 0, "CANCELLED", "98.00");

        OpsDashboardRepository.OrderTotals totals = repository.ordersCreated(WINDOW.storedFrom(), WINDOW.storedTo());

        assertThat(totals.orderCount()).isEqualTo(4);
        assertThat(totals.revenue()).isEqualByComparingTo(new BigDecimal("23.75"));
        assertThat(totals.revenue().scale()).isEqualTo(2);
    }

    @Test
    void anEmptyDayHasZeroOrdersAndZeroRevenue() {
        DashboardWindow quiet = DashboardWindow.today(Instant.parse("2031-06-10T04:00:00Z"), UTC);

        OpsDashboardRepository.OrderTotals totals = repository.ordersCreated(quiet.storedFrom(), quiet.storedTo());

        assertThat(totals.orderCount()).isZero();
        assertThat(totals.revenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(totals.revenue().scale()).isEqualTo(2);
    }

    @Test
    void pendingPaymentBacklogIgnoresCancelledAndPaidOrders() {
        long before = repository.pendingPayment();
        order("pendA", "2031-05-06 03:00:00", 0, "CREATED", "5.00");
        order("pendB", "2031-05-06 03:00:01", 0, "CONFIRMED", "5.00");
        order("pendCancelled", "2031-05-06 03:00:02", 0, "CANCELLED", "5.00");
        order("paid", "2031-05-06 03:00:03", 1, "CREATED", "5.00");

        assertThat(repository.pendingPayment() - before).isEqualTo(2);
    }

    @Test
    void paymentFailuresExcludeSystemClosuresAndRespectTheWindow() {
        order("pay", "2031-05-06 03:00:00", 0, "CREATED", "10.00");
        String orderId = "OPS" + tag + "pay";
        payment(orderId, "f1", "FAILED", "CARD_DECLINED", "2031-05-06 03:00:00.000");     // counted
        payment(orderId, "f2", "FAILED", null, "2031-05-06 04:00:00.000");                // NULL reason is a real failure
        payment(orderId, "f3", "FAILED", "CARD_DECLINED", "2031-05-05 16:00:00.000");     // start edge is inclusive
        payment(orderId, "f4", "FAILED", "CARD_DECLINED", "2031-05-06 16:00:00.000");     // end edge is exclusive
        payment(orderId, "f5", "FAILED", "CARD_DECLINED", "2031-05-05 15:59:59.999");     // just before
        payment(orderId, "f6", "FAILED", "ORDER_CANCELLED", "2031-05-06 03:00:00.000");   // system closures
        payment(orderId, "f7", "FAILED", "SUPERSEDED_BY_RETRY", "2031-05-06 03:00:00.000");
        payment(orderId, "f8", "FAILED", "SUPERSEDED_BY_LATE_SUCCESS", "2031-05-06 03:00:00.000");
        payment(orderId, "f9", "SUCCEEDED", null, "2031-05-06 03:00:00.000");             // not a failure
        // INITIATED and SUCCEEDED are each unique per order (active_order_id), hence a second order for the open attempt
        order("pay2", "2031-05-06 03:00:00", 0, "CREATED", "10.00");
        payment("OPS" + tag + "pay2", "f10", "INITIATED", null, "2031-05-06 03:00:00.000");

        assertThat(repository.paymentFailures(WINDOW.storedFrom(), WINDOW.storedTo())).isEqualTo(3);
    }

    @Test
    void refundRequiredIsABacklogNotLimitedToToday() {
        order("refund", "2031-05-06 03:00:00", 1, "CANCELLED", "10.00");
        long before = repository.refundRequired();
        payment("OPS" + tag + "refund", "r1", "REFUND_REQUIRED", "ORDER_CANCELLED", "2031-01-01 00:00:00.000");
        payment("OPS" + tag + "refund", "r2", "REFUND_REQUIRED", "ORDER_CANCELLED", "2031-05-06 03:00:00.000");

        assertThat(repository.refundRequired() - before).isEqualTo(2);
    }

    @Test
    void lowStockUsesDatabaseQuantityAndSkipsSoftDeletedProducts() {
        long before = repository.lowStockCount(5);
        insertProduct("OP" + tag + "-a", 0, false, "legacy");
        insertProduct("OP" + tag + "-b", 5, false, "legacy");   // threshold is inclusive
        insertProduct("OP" + tag + "-c", 6, false, "legacy");   // above the threshold
        insertProduct("OP" + tag + "-d", 1, true, "legacy");    // soft-deleted

        List<String> ids = repository.lowStock(5, 100_000).stream()
                .map(OpsDashboardRepository.LowStockProduct::productId).toList();

        assertThat(ids).contains("OP" + tag + "-a", "OP" + tag + "-b")
                .doesNotContain("OP" + tag + "-c", "OP" + tag + "-d");
        assertThat(ids.indexOf("OP" + tag + "-a")).isLessThan(ids.indexOf("OP" + tag + "-b"));
        assertThat(repository.lowStockCount(5) - before).isEqualTo(2);
        assertThat(repository.lowStock(5, 1)).hasSize(1);
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    private void order(String suffix, String createdAtUtc, int payStatus, String orderStatus, String price) {
        jdbc.update("INSERT INTO shop_order(order_id, member_id, price, pay_status, order_status, created_at) "
                        + "VALUES (?, 'ops-it@example.com', ?, ?, ?, ?)",
                "OPS" + tag + suffix, new BigDecimal(price), payStatus, orderStatus,
                LocalDateTime.parse(createdAtUtc.replace(' ', 'T')));
    }

    private void payment(String orderId, String tradeSuffix, String status, String reason, String updatedAtUtc) {
        LocalDateTime at = LocalDateTime.parse(updatedAtUtc.replace(' ', 'T'));
        jdbc.update("INSERT INTO payment(order_id, merchant_trade_no, provider, amount, status, failure_reason, "
                        + "created_at, updated_at) VALUES (?, ?, 'TEST', 10.00, ?, ?, ?, ?)",
                orderId, ("T" + tag + tradeSuffix), status, reason, at, at);
    }

    private void insertProduct(String productId, int quantity, boolean deleted, String creator) {
        jdbc.update("INSERT INTO product(product_id, product_name, price, quantity, creator_id, deleted_at) "
                        + "VALUES (?, 'Ops IT', 10.00, ?, ?, ?)",
                productId, quantity, creator, deleted ? LocalDateTime.of(2031, 1, 1, 0, 0) : null);
    }

    private String register(String role) throws Exception {
        String email = "ops-" + role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String credentials = mapper.writeValueAsString(Map.of("email", email, "password", "password123"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk());
        jdbc.update("UPDATE member SET role = ? WHERE email = ?", role, email);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
