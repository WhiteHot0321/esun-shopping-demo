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
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-MySQL coverage of the coupon system: ADMIN-only management, server-side pricing at checkout (both the direct
 * order API and the cart checkout), every rejection rule leaving no side effects, cancellation releasing the slot,
 * idempotent replay, and quota / per-member limits holding under concurrent checkouts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class CouponIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    /** A lock-timeout/deadlock that exhausted its retries surfaces as 409 CONCURRENT_CONFLICT; record any we see. */
    private final java.util.Queue<String> lockConflicts = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private int statusOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (!body.isEmpty() && "CONCURRENT_CONFLICT".equals(mapper.readTree(body).path("code").asText())) {
            lockConflicts.add(body);
        }
        return result.getResponse().getStatus();
    }

    @Test
    void migrationIsRepeatable() throws Exception {
        String legacyId = "LEGACY-" + tag();
        jdbc.update("INSERT INTO shop_order(order_id, member_id, price, pay_status) VALUES (?, 'legacy@example.com', 10, 0)",
                legacyId);
        for (int run = 0; run < 2; run++) {
            try (java.sql.Connection connection = dataSource.getConnection()) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.FileSystemResource("DB/14_coupon.sql"));
            }
        }
        // A pre-coupon order keeps its data and reads as "no coupon, no discount".
        Map<String, Object> legacy = jdbc.queryForMap("SELECT price, discount_amount, coupon_id, coupon_code "
                + "FROM shop_order WHERE order_id = ?", legacyId);
        assertThat(((java.math.BigDecimal) legacy.get("price")).intValue()).isEqualTo(10);
        assertThat(((java.math.BigDecimal) legacy.get("discount_amount")).signum()).isZero();
        assertThat(legacy.get("coupon_id")).isNull();
        assertThat(legacy.get("coupon_code")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'shop_order' AND column_name IN ('coupon_id', 'coupon_code', 'discount_amount')",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void onlyAdminsManageCouponsAndEveryChangeIsAudited() throws Exception {
        String tag = tag();
        String admin = adminToken("cp-admin-" + tag + "@example.com");
        String seller = sellerToken("cp-seller-" + tag + "@example.com");
        String buyer = buyerToken("cp-buyer-" + tag + "@example.com");
        String code = "AD" + tag.toUpperCase();

        for (String token : List.of(buyer, seller)) {
            mvc.perform(post("/api/admin/coupons").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(percent(code, 10))))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/coupons").header("Authorization", bearer(token))).andExpect(status().isForbidden());
            mvc.perform(put("/api/admin/coupons/1").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("active", false, "expiresAt", future(5)))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/coupons")).andExpect(status().isUnauthorized());

        // Validation: each of these is a client error and must not create a row.
        create(admin, merge(percent(code, 10), Map.of("code", "x!"))).andExpect(status().isBadRequest());
        create(admin, percent(code, 100)).andExpect(status().isBadRequest());
        create(admin, percent(code, 0)).andExpect(status().isBadRequest());
        create(admin, merge(fixed(code, 50), Map.of("maxDiscount", 10))).andExpect(status().isBadRequest());
        create(admin, merge(percent(code, 10), Map.of("expiresAt", past(1), "startsAt", past(5))))
                .andExpect(status().isBadRequest());
        create(admin, merge(percent(code, 10), Map.of("startsAt", future(9), "expiresAt", future(2))))
                .andExpect(status().isBadRequest());
        create(admin, merge(percent(code, 10), Map.of("totalQuota", 0))).andExpect(status().isBadRequest());
        assertThat(couponRows(code)).isZero();

        // Codes are stored upper-case and unique regardless of the case typed.
        String response = create(admin, percent(code.toLowerCase(), 10)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(code))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.usedCount").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(response).path("data").path("id").asLong();
        create(admin, percent(code, 20)).andExpect(status().isConflict());

        mvc.perform(put("/api/admin/coupons/{id}", id).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("active", false, "expiresAt", future(30), "totalQuota", 50))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.totalQuota").value(50));
        mvc.perform(put("/api/admin/coupons/{id}", id).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expiresAt\":\"" + future(3) + "\"}"))
                .andExpect(status().isBadRequest());
        // The dedicated toggle only flips `active`; expiry/quota set above stay exactly as they were.
        for (String token : List.of(buyer, seller)) {
            mvc.perform(post("/api/admin/coupons/{id}/active", id).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/admin/coupons/{id}/active", id).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/coupons/999999999/active").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/coupons/{id}/active", id).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.totalQuota").value(50));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'COUPON_UPDATE' AND target_id = ?",
                Integer.class, String.valueOf(id))).isEqualTo(2);
        mvc.perform(put("/api/admin/coupons/999999999").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("active", true, "expiresAt", future(5)))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/coupons").header("Authorization", bearer(admin)).param("size", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/coupons").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").isNumber());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'COUPON_CREATE' AND target_id = ?",
                Integer.class, String.valueOf(id))).isEqualTo(1);
        Map<String, Object> update = jdbc.queryForMap("SELECT actor, before_state, after_state FROM audit_log "
                + "WHERE action = 'COUPON_UPDATE' AND target_id = ? ORDER BY id LIMIT 1", String.valueOf(id));
        assertThat(update.get("actor")).isEqualTo("cp-admin-" + tag + "@example.com");
        assertThat(mapper.readTree(update.get("before_state").toString()).path("active").asBoolean()).isTrue();
        assertThat(mapper.readTree(update.get("after_state").toString()).path("active").asBoolean()).isFalse();
    }

    @Test
    void orderApiPricesTheDiscountOnTheServer() throws Exception {
        Fixture f = fixture("ord", 100, 10);
        long id = createCoupon(f.admin, merge(percent(f.code, 10), Map.of("minOrderAmount", 100, "maxDiscount", 15)));

        // The client can only name a code: a smuggled discount/price field is ignored.
        ensureAddress(f.buyer);
        String response = mvc.perform(post("/api/orders").header("Authorization", bearer(f.buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("requestId", UUID.randomUUID().toString(),
                                "couponCode", f.code.toLowerCase(), "discountAmount", 9999, "price", 1,
                                "items", List.of(Map.of("productId", f.product, "quantity", 3))))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String orderId = mapper.readTree(response).path("data").path("orderId").asText();

        // subtotal 300, 10% = 30 but capped at maxDiscount 15 -> pay 285.
        Map<String, Object> row = jdbc.queryForMap("SELECT price, discount_amount, coupon_code, coupon_id FROM shop_order "
                + "WHERE order_id = ?", orderId);
        assertThat(((java.math.BigDecimal) row.get("price")).intValue()).isEqualTo(285);
        assertThat(((java.math.BigDecimal) row.get("discount_amount")).intValue()).isEqualTo(15);
        assertThat(row.get("coupon_code")).isEqualTo(f.code);
        assertThat(((Number) row.get("coupon_id")).longValue()).isEqualTo(id);
        assertThat(quantity(f.product)).isEqualTo(7);
        assertUsage(id, f.buyerEmail, 1, 1);

        mvc.perform(get("/api/orders/{id}", orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(jsonPath("$.data.price").value(285))
                .andExpect(jsonPath("$.data.couponCode").value(f.code))
                .andExpect(jsonPath("$.data.discountAmount").value(15));
        // A buyer's order without a coupon shows no discount (partial-view hiding for sellers is covered separately).
        String plain = placeOrder(f.buyer2, f.product, 1, null);
        mvc.perform(get("/api/orders/{id}", plain).header("Authorization", bearer(f.buyer2)))
                .andExpect(jsonPath("$.data.price").value(100))
                .andExpect(jsonPath("$.data.discountAmount").value(0))
                .andExpect(jsonPath("$.data.couponCode").doesNotExist());
    }

    @Test
    void cartPreviewAndCheckoutAgreeAndReplayConsumesOnlyOneUse() throws Exception {
        Fixture f = fixture("cart", 250, 20);
        long id = createCoupon(f.admin, fixed(f.code, 60));
        ensureAddress(f.buyer);
        addToCart(f.buyer, f.product, 2);

        mvc.perform(post("/api/cart/coupon-preview").header("Authorization", bearer(f.buyer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + f.code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subtotal").value(500))
                .andExpect(jsonPath("$.data.discountAmount").value(60))
                .andExpect(jsonPath("$.data.total").value(440));
        // A preview reserves nothing.
        assertUsage(id, f.buyerEmail, 0, 0);

        String requestId = UUID.randomUUID().toString();
        String orderId = checkout(f.buyer, requestId, f.code).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        orderId = mapper.readTree(orderId).path("data").path("orderId").asText();
        assertThat(jdbc.queryForObject("SELECT price FROM shop_order WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(440);
        assertUsage(id, f.buyerEmail, 1, 1);

        // Replaying the same request id (network retry) returns the original order and burns no second use.
        String replay = checkout(f.buyer, requestId, f.code).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(mapper.readTree(replay).path("data").path("orderId").asText()).isEqualTo(orderId);
        assertUsage(id, f.buyerEmail, 1, 1);
        assertThat(quantity(f.product)).isEqualTo(18);

        // Preview with a coupon and an empty cart is refused.
        mvc.perform(post("/api/cart/coupon-preview").header("Authorization", bearer(f.buyer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + f.code + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyRejectionLeavesNoOrderStockOrCounterBehind() throws Exception {
        Fixture f = fixture("rej", 100, 10);
        long minOrder = createCoupon(f.admin, merge(fixed(f.code + "M", 10), Map.of("minOrderAmount", 500)));
        long inactive = createCoupon(f.admin, fixed(f.code + "I", 10));
        long expired = createCoupon(f.admin, fixed(f.code + "E", 10));
        long scheduled = createCoupon(f.admin, merge(fixed(f.code + "S", 10),
                Map.of("startsAt", future(2), "expiresAt", future(4))));
        long once = createCoupon(f.admin, fixed(f.code + "O", 10));
        long sold = createCoupon(f.admin, merge(fixed(f.code + "Q", 10), Map.of("totalQuota", 1)));
        mvc.perform(put("/api/admin/coupons/{id}", inactive).header("Authorization", bearer(f.admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("active", false, "expiresAt", future(5))))).andExpect(status().isOk());
        jdbc.update("UPDATE coupon SET starts_at = ?, expires_at = ? WHERE id = ?", LocalDateTime.now().minusHours(3),
                LocalDateTime.now().minusHours(1), expired);

        int before = quantity(f.product);
        int ordersBefore = orderCount(f.buyerEmail);
        placeOrderExpect(f.buyer, f.product, 1, "NOPE-" + f.tag, 404);                 // unknown
        placeOrderExpect(f.buyer, f.product, 1, "bad code!", 404);                     // malformed = same answer as unknown
        placeOrderExpect(f.buyer, f.product, 1, f.code + "I", 404);                    // disabled looks like unknown
        placeOrderExpect(f.buyer, f.product, 1, f.code + "E", 400);                    // expired
        placeOrderExpect(f.buyer, f.product, 1, f.code + "S", 400);                    // not started
        placeOrderExpect(f.buyer, f.product, 4, f.code + "M", 400);                    // subtotal 400 < min 500
        assertThat(quantity(f.product)).isEqualTo(before);
        assertThat(orderCount(f.buyerEmail)).isEqualTo(ordersBefore);
        for (long id : List.of(minOrder, inactive, expired, scheduled)) assertUsage(id, f.buyerEmail, 0, 0);

        // Per-member limit (default 1): the second use is refused and stock from that attempt is not taken.
        placeOrderExpect(f.buyer, f.product, 1, f.code + "O", 200);
        placeOrderExpect(f.buyer, f.product, 1, f.code + "O", 409);
        assertUsage(once, f.buyerEmail, 1, 1);
        assertThat(quantity(f.product)).isEqualTo(before - 1);

        // Total quota (1): a different buyer is refused once it is spent.
        placeOrderExpect(f.buyer2, f.product, 1, f.code + "Q", 200);
        placeOrderExpect(f.buyer, f.product, 1, f.code + "Q", 409);
        assertUsage(sold, f.buyerEmail, 1, 0);
        assertThat(quantity(f.product)).isEqualTo(before - 2);

        // Preview applies the same rules.
        ensureAddress(f.buyer);
        addToCart(f.buyer, f.product, 1);
        for (String suffix : List.of("I", "E", "S", "M")) {
            int expected = suffix.equals("I") ? 404 : 400;
            mvc.perform(post("/api/cart/coupon-preview").header("Authorization", bearer(f.buyer))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + f.code + suffix + "\"}"))
                    .andExpect(status().is(expected));
        }
        mvc.perform(post("/api/cart/coupon-preview").header("Authorization", bearer(f.buyer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + f.code + "O\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void discountNeverDropsThePayableAmountBelowOne() throws Exception {
        Fixture f = fixture("cap", 40, 10);
        createCoupon(f.admin, fixed(f.code + "F", 500));
        createCoupon(f.admin, percent(f.code + "P", 99));

        String big = placeOrder(f.buyer, f.product, 1, f.code + "F");
        assertThat(jdbc.queryForObject("SELECT price FROM shop_order WHERE order_id = ?", Integer.class, big)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT discount_amount FROM shop_order WHERE order_id = ?", Integer.class, big))
                .isEqualTo(39);
        // 99% of 40 = 39.6 -> rounds to 40, then is capped to subtotal-1 = 39.
        String pct = placeOrder(f.buyer2, f.product, 1, f.code + "P");
        assertThat(jdbc.queryForObject("SELECT price FROM shop_order WHERE order_id = ?", Integer.class, pct)).isEqualTo(1);
    }

    @Test
    void cancellingReleasesTheUseExactlyOnce() throws Exception {
        Fixture f = fixture("can", 100, 10);
        long id = createCoupon(f.admin, merge(fixed(f.code, 10), Map.of("totalQuota", 1)));
        String orderId = placeOrder(f.buyer, f.product, 2, f.code);
        assertUsage(id, f.buyerEmail, 1, 1);
        placeOrderExpect(f.buyer2, f.product, 1, f.code, 409);                         // quota spent

        mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(status().isOk());
        assertUsage(id, f.buyerEmail, 0, 0);
        assertThat(quantity(f.product)).isEqualTo(10);
        // A second cancel is a state-machine conflict and must not release a second time.
        mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(status().isConflict());
        assertUsage(id, f.buyerEmail, 0, 0);

        // The freed slot is usable again, by anyone.
        placeOrderExpect(f.buyer2, f.product, 1, f.code, 200);
        assertUsage(id, f.buyer2Email, 1, 1);
        // The order keeps its coupon snapshot after cancellation (history), it just no longer counts.
        assertThat(jdbc.queryForObject("SELECT coupon_code FROM shop_order WHERE order_id = ?", String.class, orderId))
                .isEqualTo(f.code);
    }

    @Test
    void repeatedAndConcurrentCancelsReleaseASlotExactlyOnce() throws Exception {
        Fixture f = fixture("rel", 100, 20);
        // Two live redemptions: a double release of one order would show up as a counter below the real value
        // (the used_count > 0 clamps only hide it when a single order is live).
        long id = createCoupon(f.admin, merge(fixed(f.code, 10), Map.of("totalQuota", 2)));
        String first = placeOrder(f.buyer, f.product, 1, f.code);
        placeOrder(f.buyer2, f.product, 1, f.code);
        assertUsage(id, f.buyerEmail, 2, 1);

        List<Integer> codes = runConcurrently(5, i -> cancelStatus(f.buyer, first));
        assertThat(codes).filteredOn(c -> c == 200).hasSize(1);
        assertThat(codes).filteredOn(c -> c == 409).hasSize(4);
        assertThat(lockConflicts).isEmpty();
        assertUsage(id, f.buyerEmail, 1, 0);
        assertUsage(id, f.buyer2Email, 1, 1);
        assertInvariants(id);
        // Another attempt to cancel changes nothing.
        assertThat(cancelStatus(f.buyer, first)).isEqualTo(409);
        assertUsage(id, f.buyerEmail, 1, 0);
    }

    @Test
    void cancelAndCheckoutOfTheSameBuyerAndAddressDoNotDeadlock() throws Exception {
        // Regression for a lock-order cycle found in review: cancel used to X-lock the joined shipping_address row
        // (order + address -> coupon) while checkout held the coupon and needed the same address for its FK check
        // (coupon -> address). Same buyer, same default address, coupon usable more than once, repeated rounds.
        Fixture f = fixture("dl", 100, 500);
        long id = createCoupon(f.admin, merge(fixed(f.code, 10), Map.of("perMemberLimit", 50)));
        ensureAddress(f.buyer);
        for (int round = 0; round < 8; round++) {
            List<String> victims = new ArrayList<>();
            for (int i = 0; i < 4; i++) victims.add(placeOrder(f.buyer, f.product, 1, f.code));
            List<Integer> codes = runConcurrently(8, i -> i < 4
                    ? cancelStatus(f.buyer, victims.get(i))
                    : placeOrderStatus(f.buyer, f.product, 1, f.code));
            assertThat(codes).as("round %d", round).noneMatch(c -> c >= 500);
            assertThat(codes.subList(0, 4)).as("round %d cancels", round).allMatch(c -> c == 200);
            assertThat(codes.subList(4, 8)).as("round %d checkouts", round).allMatch(c -> c == 200);
        }
        assertThat(lockConflicts).as("lock conflicts").isEmpty();
        assertInvariants(id);
    }

    @Test
    void sellersSeeNoCouponDetailsOnOrdersTheyOnlyPartlyOwn() throws Exception {
        Fixture f = fixture("sel", 100, 20);
        String otherSeller = sellerToken("sel-other-" + f.tag + "@example.com");
        String otherProduct = "O-" + f.tag;
        mvc.perform(post("/api/admin/products").header("Authorization", bearer(otherSeller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("productId", otherProduct, "productName", "Other",
                                "price", 300, "quantity", 20))))
                .andExpect(status().isOk());
        createCoupon(f.admin, fixed(f.code, 40));
        ensureAddress(f.buyer);
        String response = mvc.perform(post("/api/orders").header("Authorization", bearer(f.buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("requestId", UUID.randomUUID().toString(),
                                "couponCode", f.code, "items", List.of(
                                        Map.of("productId", f.product, "quantity", 1),
                                        Map.of("productId", otherProduct, "quantity", 1))))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String orderId = mapper.readTree(response).path("data").path("orderId").asText();

        // Buyer and admin see the whole order: 100 + 300 - 40 = 360.
        mvc.perform(get("/api/orders/{id}", orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(jsonPath("$.data.price").value(360))
                .andExpect(jsonPath("$.data.discountAmount").value(40))
                .andExpect(jsonPath("$.data.couponCode").value(f.code));
        mvc.perform(get("/api/admin/orders/{id}", orderId).header("Authorization", bearer(f.admin)))
                .andExpect(jsonPath("$.data.price").value(360))
                .andExpect(jsonPath("$.data.discountAmount").value(40));
        // Each seller sees only their own line at its undiscounted price and no coupon information at all.
        mvc.perform(get("/api/seller/orders/{id}", orderId).header("Authorization", bearer(f.seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(100))
                .andExpect(jsonPath("$.data.discountAmount").value(0))
                .andExpect(jsonPath("$.data.couponCode").doesNotExist())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/seller/orders/{id}", orderId).header("Authorization", bearer(otherSeller)))
                .andExpect(jsonPath("$.data.price").value(300))
                .andExpect(jsonPath("$.data.discountAmount").value(0))
                .andExpect(jsonPath("$.data.couponCode").doesNotExist());
    }

    @Test
    void concurrentCheckoutsCannotExceedTheTotalQuota() throws Exception {
        Fixture f = fixture("quota", 100, 200);
        int quota = 3;
        long id = createCoupon(f.admin, merge(fixed(f.code, 10), Map.of("totalQuota", quota)));
        int buyers = 12;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            String token = buyerToken("q" + i + "-" + f.tag + "@example.com");
            ensureAddress(token);
            tokens.add(token);
        }
        List<Integer> codes = runConcurrently(buyers, i -> placeOrderStatus(tokens.get(i), f.product, 1, f.code));

        assertThat(codes).filteredOn(c -> c == 200).hasSize(quota);
        assertThat(codes).filteredOn(c -> c == 409).hasSize(buyers - quota);
        assertThat(lockConflicts).as("lock conflicts").isEmpty();
        assertInvariants(id);
        assertThat(usedCount(id)).isEqualTo(quota);
        assertThat(quantity(f.product)).isEqualTo(200 - quota); // failed attempts took no stock
    }

    @Test
    void concurrentCheckoutsCannotBypassThePerMemberLimit() throws Exception {
        Fixture f = fixture("mem", 100, 200);
        long id = createCoupon(f.admin, merge(fixed(f.code, 10), Map.of("perMemberLimit", 2)));
        ensureAddress(f.buyer);
        int attempts = 8;
        List<Integer> codes = runConcurrently(attempts, i -> placeOrderStatus(f.buyer, f.product, 1, f.code));

        assertThat(codes).filteredOn(c -> c == 200).hasSize(2);
        assertThat(codes).filteredOn(c -> c == 409).hasSize(attempts - 2);
        assertThat(lockConflicts).as("lock conflicts").isEmpty();
        assertUsage(id, f.buyerEmail, 2, 2);
        assertInvariants(id);
        assertThat(quantity(f.product)).isEqualTo(198);
    }

    @Test
    void concurrentCancelsAndRedemptionsStayConsistentWithoutDeadlocks() throws Exception {
        Fixture f = fixture("mix", 100, 300);
        int quota = 4;
        long id = createCoupon(f.admin, merge(fixed(f.code, 10), Map.of("totalQuota", quota)));
        List<String> holderTokens = new ArrayList<>();
        List<String> holderOrders = new ArrayList<>();
        for (int i = 0; i < quota; i++) {
            String token = buyerToken("h" + i + "-" + f.tag + "@example.com");
            holderTokens.add(token);
            holderOrders.add(placeOrder(token, f.product, 1, f.code));
        }
        assertThat(usedCount(id)).isEqualTo(quota);

        int challengers = 8;
        List<String> challengerTokens = new ArrayList<>();
        for (int i = 0; i < challengers; i++) {
            String token = buyerToken("c" + i + "-" + f.tag + "@example.com");
            ensureAddress(token);
            challengerTokens.add(token);
        }
        // Holders cancel (release + restore stock of the SAME product) while challengers race for the freed slots.
        List<Integer> codes = runConcurrently(quota + challengers, i -> i < quota
                ? cancelStatus(holderTokens.get(i), holderOrders.get(i))
                : placeOrderStatus(challengerTokens.get(i - quota), f.product, 1, f.code));

        assertThat(codes).noneMatch(c -> c >= 500);
        // A 409 caused by an exhausted deadlock retry would look like a legitimate "quota spent" answer.
        assertThat(lockConflicts).as("lock conflicts").isEmpty();
        assertThat(codes.subList(0, quota)).allMatch(c -> c == 200);
        assertThat(codes.subList(quota, codes.size())).allMatch(c -> c == 200 || c == 409);
        assertInvariants(id);
        assertThat(usedCount(id)).isBetween(0, quota);
        // Stock reconciles: 300 minus every order that is not cancelled.
        int live = jdbc.queryForObject("SELECT COUNT(*) FROM shop_order o JOIN order_detail d ON d.order_id = o.order_id "
                + "WHERE d.product_id = ? AND o.order_status <> 'CANCELLED'", Integer.class, f.product);
        assertThat(quantity(f.product)).isEqualTo(300 - live);
    }

    // ---- helpers ----

    private record Fixture(String tag, String admin, String seller, String buyer, String buyer2, String buyerEmail,
                           String buyer2Email, String product, String code) { }

    private Fixture fixture(String prefix, int price, int stock) throws Exception {
        String tag = tag();
        String admin = adminToken(prefix + "-admin-" + tag + "@example.com");
        String seller = sellerToken(prefix + "-seller-" + tag + "@example.com");
        String buyerEmail = prefix + "-buyer-" + tag + "@example.com";
        String buyer2Email = prefix + "-buyer2-" + tag + "@example.com";
        String buyer = buyerToken(buyerEmail);
        String buyer2 = buyerToken(buyer2Email);
        String product = "P-" + tag;
        mvc.perform(post("/api/admin/products").header("Authorization", bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("productId", product, "productName", "Item " + product,
                                "price", price, "quantity", stock))))
                .andExpect(status().isOk());
        return new Fixture(tag, admin, seller, buyer, buyer2, buyerEmail, buyer2Email, product, "T" + tag.toUpperCase());
    }

    private Map<String, Object> percent(String code, int value) {
        return baseCoupon(code, "PERCENT", value);
    }

    private Map<String, Object> fixed(String code, int value) {
        return baseCoupon(code, "FIXED", value);
    }

    private Map<String, Object> baseCoupon(String code, String type, int value) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("discountType", type);
        body.put("discountValue", value);
        body.put("startsAt", past(1));
        body.put("expiresAt", future(24));
        return body;
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overrides) {
        Map<String, Object> merged = new HashMap<>(base);
        merged.putAll(overrides);
        return merged;
    }

    private static String future(int hours) {
        return LocalDateTime.now().plusHours(hours).withNano(0).toString();
    }

    private static String past(int hours) {
        return LocalDateTime.now().minusHours(hours).withNano(0).toString();
    }

    private org.springframework.test.web.servlet.ResultActions create(String adminToken, Map<String, Object> body)
            throws Exception {
        return mvc.perform(post("/api/admin/coupons").header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private long createCoupon(String adminToken, Map<String, Object> body) throws Exception {
        String response = create(adminToken, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("id").asLong();
    }

    private int couponRows(String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE code = ?", Integer.class, code);
    }

    private int usedCount(long couponId) {
        return jdbc.queryForObject("SELECT used_count FROM coupon WHERE id = ?", Integer.class, couponId);
    }

    private void assertUsage(long couponId, String memberEmail, int global, int member) {
        assertThat(usedCount(couponId)).as("coupon used_count").isEqualTo(global);
        List<Integer> rows = jdbc.queryForList("SELECT used_count FROM coupon_member_usage WHERE coupon_id = ? AND member_id = ?",
                Integer.class, couponId, memberEmail);
        assertThat(rows.isEmpty() ? 0 : rows.get(0)).as("member used_count").isEqualTo(member);
    }

    /** The counters must always equal what the order table says: live (not cancelled) orders carrying the coupon. */
    private void assertInvariants(long couponId) {
        assertThat(usedCount(couponId)).isEqualTo(jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_order WHERE coupon_id = ? AND order_status <> 'CANCELLED'", Integer.class, couponId));
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(used_count), 0) FROM coupon_member_usage WHERE coupon_id = ?",
                Integer.class, couponId)).isEqualTo(usedCount(couponId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE id = ? AND total_quota IS NOT NULL "
                + "AND used_count > total_quota", Integer.class, couponId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_order WHERE coupon_id = ? AND discount_amount <= 0",
                Integer.class, couponId)).isZero();
    }

    private int quantity(String productId) {
        return jdbc.queryForObject("SELECT quantity FROM product WHERE product_id = ?", Integer.class, productId);
    }

    private int orderCount(String memberEmail) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shop_order WHERE member_id = ?", Integer.class, memberEmail);
    }

    private void ensureAddress(String buyerToken) throws Exception {
        mvc.perform(post("/api/member/addresses").header("Authorization", bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("label", "住家", "receiverName", "王小明",
                                "phone", "0912-345-678", "postalCode", "100", "address", "台北市中正區測試路 1 號",
                                "isDefault", true))))
                .andExpect(status().isOk());
    }

    private int placeOrderStatus(String buyerToken, String productId, int quantity, String couponCode) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("items", List.of(Map.of("productId", productId, "quantity", quantity)));
        if (couponCode != null) body.put("couponCode", couponCode);
        return statusOf(mvc.perform(post("/api/orders").header("Authorization", bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andReturn());
    }

    private void placeOrderExpect(String buyerToken, String productId, int quantity, String couponCode, int expected)
            throws Exception {
        ensureAddress(buyerToken);
        assertThat(placeOrderStatus(buyerToken, productId, quantity, couponCode)).isEqualTo(expected);
    }

    private String placeOrder(String buyerToken, String productId, int quantity, String couponCode) throws Exception {
        ensureAddress(buyerToken);
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("items", List.of(Map.of("productId", productId, "quantity", quantity)));
        if (couponCode != null) body.put("couponCode", couponCode);
        MvcResult result = mvc.perform(post("/api/orders").header("Authorization", bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("orderId").asText();
    }

    private int cancelStatus(String buyerToken, String orderId) throws Exception {
        return statusOf(mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", bearer(buyerToken)))
                .andReturn());
    }

    private void addToCart(String buyerToken, String productId, int quantity) throws Exception {
        mvc.perform(post("/api/cart/add").header("Authorization", bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("productId", productId, "quantity", quantity))))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions checkout(String buyerToken, String requestId, String code)
            throws Exception {
        Long addressId = mapper.readTree(mvc.perform(get("/api/member/addresses").header("Authorization", bearer(buyerToken)))
                .andReturn().getResponse().getContentAsString()).path("data").path(0).path("id").asLong();
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("shippingAddressId", addressId);
        if (code != null) body.put("couponCode", code);
        return mvc.perform(post("/api/cart/checkout").header("Authorization", bearer(buyerToken))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private interface IndexedCall {
        int run(int index) throws Exception;
    }

    /** Releases all callers together so their transactions genuinely overlap. */
    private List<Integer> runConcurrently(int callers, IndexedCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                int index = i;
                tasks.add(() -> {
                    ready.countDown();
                    go.await();
                    return call.run(index);
                });
            }
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) futures.add(pool.submit(task));
            ready.await();
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) codes.add(future.get());
            return codes;
        } finally {
            pool.shutdownNow();
        }
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
