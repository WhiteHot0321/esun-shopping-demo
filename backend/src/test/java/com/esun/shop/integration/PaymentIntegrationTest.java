package com.esun.shop.integration;

import com.esun.shop.model.PaymentResult;
import com.esun.shop.service.HmacPaymentGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-MySQL coverage of payment integration: server-decided payStatus, ownership, signed idempotent callbacks,
 * retry after failure, cancel/callback races and the DB-level "one live attempt per order" invariant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.provider=sandbox")
class PaymentIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired HmacPaymentGateway gateway;
    @Autowired javax.sql.DataSource dataSource;

    // ---- the vulnerability this feature closes ----

    @Test
    void clientCannotDeclareItsOwnOrderPaid() throws Exception {
        String tag = tag();
        String seller = token("pay-seller-" + tag + "@example.com", "SELLER");
        String buyer = token("pay-buyer-" + tag + "@example.com", "BUYER");
        createProduct(seller, "PS-" + tag, 100, 10);
        String orderId = placeOrder(buyer, "PS-" + tag, 1, "PAID");

        assertThat(payStatusColumn(orderId)).isZero();
        mvc.perform(get("/api/orders/{id}", orderId).header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.data.payStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.payable").value(true))
                .andExpect(jsonPath("$.data.paymentStatus").doesNotExist());
    }

    @Test
    void payStatusFieldIsOptionalOnOrderCreation() throws Exception {
        String tag = tag();
        String seller = token("pso-seller-" + tag + "@example.com", "SELLER");
        String buyer = token("pso-buyer-" + tag + "@example.com", "BUYER");
        createProduct(seller, "PO-" + tag, 100, 10);
        String orderId = placeOrder(buyer, "PO-" + tag, 1, null);
        assertThat(payStatusColumn(orderId)).isZero();
    }

    // ---- starting a payment ----

    @Test
    void startPaymentIsOwnerOnlyServerPricedAndIdempotent() throws Exception {
        Fixture f = fixture(2, 150);
        String stranger = token("pay-stranger-" + f.tag + "@example.com", "BUYER");

        mvc.perform(post("/api/orders/{id}/payment", f.orderId)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/{id}/payment", f.orderId).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());

        JsonNode first = json(mvc.perform(post("/api/orders/{id}/payment", f.orderId)
                        .header("Authorization", bearer(f.buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INITIATED"))
                .andExpect(jsonPath("$.data.amount").value(300))
                .andExpect(jsonPath("$.data.provider").value("sandbox"))
                .andExpect(jsonPath("$.data.simulatable").value(true)).andReturn().getResponse().getContentAsString());
        JsonNode second = json(mvc.perform(post("/api/orders/{id}/payment", f.orderId)
                        .header("Authorization", bearer(f.buyer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(second.path("data").path("merchantTradeNo").asText())
                .isEqualTo(first.path("data").path("merchantTradeNo").asText());
        assertThat(paymentCount(f.orderId)).isEqualTo(1);
        // the order's own state is untouched until a verified callback arrives
        assertThat(payStatusColumn(f.orderId)).isZero();
    }

    @Test
    void concurrentStartsOpenExactlyOneAttempt() throws Exception {
        Fixture f = fixture(1, 100);
        int callers = 8;
        List<Callable<String>> tasks = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < callers; i++) {
            tasks.add(() -> {
                go.await();
                var res = mvc.perform(post("/api/orders/{id}/payment", f.orderId)
                        .header("Authorization", bearer(f.buyer))).andReturn().getResponse();
                assertThat(res.getStatus()).isEqualTo(200);
                return mapper.readTree(res.getContentAsString()).path("data").path("merchantTradeNo").asText();
            });
        }
        List<String> tradeNos = runConcurrently(tasks, go);
        assertThat(tradeNos).hasSize(callers).containsOnly(tradeNos.get(0));
        assertThat(paymentCount(f.orderId)).isEqualTo(1);
    }

    // ---- callback authentication ----

    @Test
    void callbackRejectsBadSignatureUnknownTradeNoAndAmountMismatchWithoutChangingState() throws Exception {
        Fixture f = fixture(1, 100);
        String tradeNo = startPayment(f);

        // forged: signature for a different amount
        callback(tradeNo, "100.00", "SUCCESS", "REF", gateway.sign(tradeNo, new BigDecimal("1.00"),
                PaymentResult.SUCCESS, "REF")).andExpect(status().isBadRequest());
        // missing signature field entirely
        mvc.perform(post("/api/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantTradeNo\":\"" + tradeNo + "\",\"amount\":100,\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest());
        // validly signed but for a trade number that does not exist
        String ghost = "PAYghost" + tag();
        callback(ghost, "100.00", "SUCCESS", "REF", gateway.sign(ghost, new BigDecimal("100.00"),
                PaymentResult.SUCCESS, "REF")).andExpect(status().isNotFound());
        // forged signature for a trade number that does not exist is indistinguishable from any other bad signature
        callback(ghost, "100.00", "SUCCESS", "REF", "0".repeat(64)).andExpect(status().isBadRequest());
        // validly signed FAILED with the wrong amount: no money moved, so just refuse it
        callback(tradeNo, "99.00", "FAILED", null, gateway.sign(tradeNo, new BigDecimal("99.00"),
                PaymentResult.FAILED, null)).andExpect(status().isBadRequest());
        // absurd amounts are rejected by validation before any arithmetic or lookup
        mvc.perform(post("/api/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantTradeNo\":\"" + tradeNo + "\",\"amount\":1E+100000000,"
                                + "\"result\":\"SUCCESS\",\"signature\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantTradeNo\":\"" + tradeNo + "\",\"amount\":-5,"
                                + "\"result\":\"SUCCESS\",\"signature\":\"x\"}"))
                .andExpect(status().isBadRequest());

        assertThat(paymentStatus(tradeNo)).isEqualTo("INITIATED");
        assertThat(payStatusColumn(f.orderId)).isZero();
    }

    @Test
    void authenticSuccessForADifferentAmountIsParkedForRefundNotAppliedNorDropped() throws Exception {
        Fixture f = fixture(1, 100);
        String tradeNo = startPayment(f);

        signedCallback(tradeNo, "99.00", PaymentResult.SUCCESS, "REF-M")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("REFUND_REQUIRED"));
        assertThat(paymentStatus(tradeNo)).isEqualTo("REFUND_REQUIRED");
        assertThat(payStatusColumn(f.orderId)).isZero();
        // the order is still payable: a fresh attempt can be opened
        assertThat(startPayment(f)).isNotEqualTo(tradeNo);
    }

    @Test
    void staleFailureAfterCancellationChangesNothing() throws Exception {
        Fixture f = fixture(1, 100);
        String tradeNo = startPayment(f);
        mvc.perform(post("/api/orders/{id}/cancel", f.orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(status().isOk());
        signedCallback(tradeNo, "100.00", PaymentResult.FAILED, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("DUPLICATE"));
        assertThat(paymentStatus(tradeNo)).isEqualTo("FAILED");
    }

    // ---- happy path + idempotency ----

    @Test
    void verifiedSuccessCallbackMarksPaidAndReplaysAreIdempotent() throws Exception {
        Fixture f = fixture(1, 250);
        String tradeNo = startPayment(f);

        signedCallback(tradeNo, "250.00", PaymentResult.SUCCESS, "REF-1")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("SUCCEEDED"));
        assertThat(payStatusColumn(f.orderId)).isEqualTo(1);
        assertThat(paymentStatus(tradeNo)).isEqualTo("SUCCEEDED");
        Object paidAt = jdbc.queryForObject("SELECT paid_at FROM payment WHERE merchant_trade_no = ?", Object.class, tradeNo);
        assertThat(paidAt).isNotNull();

        // replay, and a stale failure that must never undo a success
        signedCallback(tradeNo, "250.00", PaymentResult.SUCCESS, "REF-1")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("DUPLICATE"));
        signedCallback(tradeNo, "250.00", PaymentResult.FAILED, "REF-1")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("DUPLICATE"));
        assertThat(paymentStatus(tradeNo)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT paid_at FROM payment WHERE merchant_trade_no = ?", Object.class, tradeNo))
                .isEqualTo(paidAt);

        // views: buyer sees PAID and cannot pay again; seller sees the paid state
        mvc.perform(get("/api/orders/{id}", f.orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(jsonPath("$.data.payStatus").value("PAID"))
                .andExpect(jsonPath("$.data.paymentStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.payable").value(false));
        mvc.perform(get("/api/seller/orders/{id}", f.orderId).header("Authorization", bearer(f.seller)))
                .andExpect(jsonPath("$.data.payStatus").value("PAID"))
                .andExpect(jsonPath("$.data.payable").value(false));
        mvc.perform(post("/api/orders/{id}/payment", f.orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentDuplicateSuccessCallbacksApplyExactlyOnce() throws Exception {
        Fixture f = fixture(1, 100);
        String tradeNo = startPayment(f);
        String body = signedBody(tradeNo, "100.00", PaymentResult.SUCCESS, "REF-C");

        int callers = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            tasks.add(() -> {
                go.await();
                var res = mvc.perform(post("/api/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andReturn().getResponse();
                assertThat(res.getStatus()).isEqualTo(200);
                return mapper.readTree(res.getContentAsString()).path("data").path("outcome").asText();
            });
        }
        List<String> outcomes = runConcurrently(tasks, go);
        assertThat(outcomes).filteredOn("SUCCEEDED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("DUPLICATE"::equals).hasSize(callers - 1);
        assertThat(payStatusColumn(f.orderId)).isEqualTo(1);
    }

    // ---- failure, retry, late money ----

    @Test
    void failedAttemptCanBeRetriedAndLateMoneyOnTheOldAttemptIsFlaggedForRefund() throws Exception {
        Fixture f = fixture(1, 100);
        String first = startPayment(f);
        signedCallback(first, "100.00", PaymentResult.FAILED, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("FAILED"));
        assertThat(paymentStatus(first)).isEqualTo("FAILED");
        assertThat(payStatusColumn(f.orderId)).isZero();
        mvc.perform(get("/api/orders/{id}", f.orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(jsonPath("$.data.paymentStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.payable").value(true));

        String second = startPayment(f);
        assertThat(second).isNotEqualTo(first);
        signedCallback(second, "100.00", PaymentResult.SUCCESS, "REF-2")
                .andExpect(jsonPath("$.data.outcome").value("SUCCEEDED"));
        assertThat(payStatusColumn(f.orderId)).isEqualTo(1);

        // the provider now (wrongly) also reports success for the attempt we had closed: never double-count it
        signedCallback(first, "100.00", PaymentResult.SUCCESS, "REF-1")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("REFUND_REQUIRED"));
        assertThat(paymentStatus(first)).isEqualTo("REFUND_REQUIRED");
        assertThat(paymentStatus(second)).isEqualTo("SUCCEEDED");
        assertThat(payStatusColumn(f.orderId)).isEqualTo(1);
    }

    @Test
    void moneyTakenOnAnAttemptWeHadClosedIsAppliedWhileTheOrderIsStillWaitingForPayment() throws Exception {
        Fixture f = fixture(1, 100);
        String declined = startPayment(f);
        signedCallback(declined, "100.00", PaymentResult.FAILED, null).andExpect(status().isOk());
        String retry = startPayment(f); // a newer live attempt exists
        assertThat(paymentStatus(retry)).isEqualTo("INITIATED");

        // the provider now reports the declined attempt as paid after all: the buyer paid, the order was unpaid
        signedCallback(declined, "100.00", PaymentResult.SUCCESS, "REF-LATE")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("SUCCEEDED"));
        assertThat(paymentStatus(declined)).isEqualTo("SUCCEEDED");
        assertThat(paymentStatus(retry)).isEqualTo("FAILED"); // superseded so only one live attempt remains
        assertThat(payStatusColumn(f.orderId)).isEqualTo(1);
    }

    // ---- interaction with cancellation ----

    @Test
    void cancellingBeforePaymentClosesTheAttemptAndBlocksFurtherPayment() throws Exception {
        Fixture f = fixture(3, 100);
        String tradeNo = startPayment(f);
        assertThat(quantity(f.productId)).isEqualTo(7);

        mvc.perform(post("/api/orders/{id}/cancel", f.orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(status().isOk());
        assertThat(paymentStatus(tradeNo)).isEqualTo("FAILED");
        assertThat(quantity(f.productId)).isEqualTo(10);
        mvc.perform(post("/api/orders/{id}/payment", f.orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/orders/{id}", f.orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(jsonPath("$.data.payable").value(false));

        // provider still completes the payment the buyer had already started: money must be flagged, order untouched
        signedCallback(tradeNo, "300.00", PaymentResult.SUCCESS, "REF-L")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("REFUND_REQUIRED"));
        assertThat(paymentStatus(tradeNo)).isEqualTo("REFUND_REQUIRED");
        assertThat(payStatusColumn(f.orderId)).isZero();
        assertThat(orderStatus(f.orderId)).isEqualTo("CANCELLED");
    }

    @Test
    void cancellingAPaidOrderFlagsTheMoneyForRefundAndReturnsStock() throws Exception {
        Fixture f = fixture(2, 100);
        String tradeNo = startPayment(f);
        signedCallback(tradeNo, "200.00", PaymentResult.SUCCESS, "REF-P").andExpect(status().isOk());
        assertThat(quantity(f.productId)).isEqualTo(8);

        mvc.perform(post("/api/orders/{id}/cancel", f.orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payStatus").value("PAID"))
                .andExpect(jsonPath("$.data.paymentStatus").value("REFUND_REQUIRED"))
                .andExpect(jsonPath("$.data.payable").value(false));
        assertThat(paymentStatus(tradeNo)).isEqualTo("REFUND_REQUIRED");
        assertThat(quantity(f.productId)).isEqualTo(10);
    }

    @Test
    void successCallbackRacingACancellationAlwaysEndsInTheSameConsistentState() throws Exception {
        for (int round = 0; round < 5; round++) {
            Fixture f = fixture(2, 100);
            String tradeNo = startPayment(f);
            String body = signedBody(tradeNo, "200.00", PaymentResult.SUCCESS, "REF-R" + round);

            CountDownLatch go = new CountDownLatch(1);
            List<Callable<Integer>> tasks = List.of(
                    () -> {
                        go.await();
                        return mvc.perform(post("/api/payments/callback").contentType(MediaType.APPLICATION_JSON)
                                .content(body)).andReturn().getResponse().getStatus();
                    },
                    () -> {
                        go.await();
                        return mvc.perform(post("/api/orders/{id}/cancel", f.orderId)
                                .header("Authorization", bearer(f.buyer))).andReturn().getResponse().getStatus();
                    });
            assertThat(runConcurrently(tasks, go)).containsExactly(200, 200);

            // Whichever won the order lock first, the end state is identical: cancelled, money flagged, stock returned once.
            assertThat(orderStatus(f.orderId)).as("round %d", round).isEqualTo("CANCELLED");
            assertThat(paymentStatus(tradeNo)).as("round %d", round).isEqualTo("REFUND_REQUIRED");
            assertThat(quantity(f.productId)).as("round %d", round).isEqualTo(10);
        }
    }

    // ---- sandbox simulator ----

    @Test
    void sandboxResultIsOwnerOnlyAndGoesThroughTheSignedCallbackPath() throws Exception {
        Fixture f = fixture(1, 120);
        String stranger = token("pay-sim-stranger-" + f.tag + "@example.com", "BUYER");
        String tradeNo = startPayment(f);

        mvc.perform(post("/api/payments/{no}/sandbox-result", tradeNo).header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound());
        assertThat(paymentStatus(tradeNo)).isEqualTo("INITIATED");

        mvc.perform(post("/api/payments/{no}/sandbox-result", tradeNo).header("Authorization", bearer(f.buyer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"FAILED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"));
        String retry = startPayment(f);
        mvc.perform(post("/api/payments/{no}/sandbox-result", retry).header("Authorization", bearer(f.buyer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.paidAt").exists());
        assertThat(payStatusColumn(f.orderId)).isEqualTo(1);
        // the provider's own transaction reference is kept on the ledger row for reconciliation
        assertThat(jdbc.queryForObject("SELECT provider_ref FROM payment WHERE merchant_trade_no = ?", String.class, retry))
                .startsWith("SBX-");
    }

    // ---- database invariants + migration ----

    @Test
    void databaseRefusesASecondLiveAttemptForTheSameOrder() throws Exception {
        Fixture f = fixture(1, 100);
        startPayment(f);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO payment(order_id, merchant_trade_no, provider, amount, status) "
                + "VALUES (?, ?, 'sandbox', 100, 'INITIATED')", f.orderId, "PAYdup" + tag()))
                .isInstanceOf(DuplicateKeyException.class);
        // closed attempts may accumulate freely
        jdbc.update("INSERT INTO payment(order_id, merchant_trade_no, provider, amount, status) "
                + "VALUES (?, ?, 'sandbox', 100, 'FAILED')", f.orderId, "PAYold1" + tag());
        jdbc.update("INSERT INTO payment(order_id, merchant_trade_no, provider, amount, status) "
                + "VALUES (?, ?, 'sandbox', 100, 'FAILED')", f.orderId, "PAYold2" + tag());
        // and a non-positive amount is refused by the CHECK constraint
        assertThatThrownBy(() -> jdbc.update("INSERT INTO payment(order_id, merchant_trade_no, provider, amount, status) "
                + "VALUES (?, ?, 'sandbox', 0, 'FAILED')", f.orderId, "PAYzero" + tag()))
                .hasMessageContaining("Check constraint"); // MySQL 3819; Spring does not map it to an integrity exception
    }

    @Test
    void migrationIsRepeatableAndKeepsExistingPayments() throws Exception {
        Fixture f = fixture(1, 100);
        String tradeNo = startPayment(f);
        for (int run = 0; run < 2; run++) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(dataSource.getConnection(),
                    new org.springframework.core.io.FileSystemResource("DB/13_payment.sql"));
            assertThat(paymentStatus(tradeNo)).as("run %d", run + 1).isEqualTo("INITIATED");
        }
    }

    // ---- helpers ----

    private record Fixture(String tag, String seller, String buyer, String productId, String orderId) { }

    private Fixture fixture(int quantity, int price) throws Exception {
        String tag = tag();
        String seller = token("pay-seller-" + tag + "@example.com", "SELLER");
        String buyer = token("pay-buyer-" + tag + "@example.com", "BUYER");
        String productId = "PP-" + tag;
        createProduct(seller, productId, price, 10);
        return new Fixture(tag, seller, buyer, productId, placeOrder(buyer, productId, quantity, "PENDING"));
    }

    private String startPayment(Fixture f) throws Exception {
        String body = mvc.perform(post("/api/orders/{id}/payment", f.orderId).header("Authorization", bearer(f.buyer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("merchantTradeNo").asText();
    }

    private String signedBody(String tradeNo, String amount, PaymentResult result, String providerRef) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("merchantTradeNo", tradeNo);
        body.put("amount", new BigDecimal(amount));
        body.put("result", result.name());
        body.put("providerRef", providerRef);
        body.put("signature", gateway.sign(tradeNo, new BigDecimal(amount), result, providerRef));
        return mapper.writeValueAsString(body);
    }

    private org.springframework.test.web.servlet.ResultActions signedCallback(String tradeNo, String amount,
                                                                              PaymentResult result, String providerRef)
            throws Exception {
        return mvc.perform(post("/api/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content(signedBody(tradeNo, amount, result, providerRef)));
    }

    private org.springframework.test.web.servlet.ResultActions callback(String tradeNo, String amount, String result,
                                                                        String providerRef, String signature)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("merchantTradeNo", tradeNo);
        body.put("amount", new BigDecimal(amount));
        body.put("result", result);
        body.put("providerRef", providerRef);
        body.put("signature", signature);
        return mvc.perform(post("/api/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks, CountDownLatch go) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) futures.add(pool.submit(task));
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private int payStatusColumn(String orderId) {
        return jdbc.queryForObject("SELECT pay_status FROM shop_order WHERE order_id = ?", Integer.class, orderId);
    }

    private String orderStatus(String orderId) {
        return jdbc.queryForObject("SELECT order_status FROM shop_order WHERE order_id = ?", String.class, orderId);
    }

    private String paymentStatus(String tradeNo) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE merchant_trade_no = ?", String.class, tradeNo);
    }

    private int paymentCount(String orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, orderId);
    }

    private int quantity(String productId) {
        return jdbc.queryForObject("SELECT quantity FROM product WHERE product_id = ?", Integer.class, productId);
    }

    private JsonNode json(String body) throws Exception {
        return mapper.readTree(body);
    }

    private void createProduct(String sellerToken, String id, int price, int quantity) throws Exception {
        mvc.perform(post("/api/admin/products").header("Authorization", bearer(sellerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("productId", id, "productName", "Item " + id,
                                "price", price, "quantity", quantity))))
                .andExpect(status().isOk());
    }

    /** @param payStatus what the (hostile or buggy) client claims; null omits the field entirely */
    private String placeOrder(String buyerToken, String productId, int quantity, String payStatus) throws Exception {
        mvc.perform(post("/api/member/addresses").header("Authorization", bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("label", "住家", "receiverName", "王小明",
                                "phone", "0912-345-678", "postalCode", "100", "address", "台北市中正區測試路 1 號",
                                "isDefault", true))))
                .andExpect(status().isOk());
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("items", List.of(Map.of("productId", productId, "quantity", quantity)));
        if (payStatus != null) body.put("payStatus", payStatus);
        String response = mvc.perform(post("/api/orders").header("Authorization", bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("orderId").asText();
    }

    private String token(String email, String role) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk());
        if (!"BUYER".equals(role)) jdbc.update("UPDATE member SET role = ? WHERE email = ?", role, email);
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
