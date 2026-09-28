package com.esun.shop.integration;

import com.esun.shop.dto.ReviewRequest;
import com.esun.shop.model.Member;
import com.esun.shop.model.ProductReview;
import com.esun.shop.repository.ProductReviewRepository;
import com.esun.shop.service.AuditLogService;
import com.esun.shop.service.ProductReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Real-MySQL proof that review moderation serializes the source of its audit snapshots as well
 * as its UPDATE.  The spy controls only the commit boundary; both moderation calls and all SQL
 * execute against the Testcontainers MySQL instance.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=10")
class ProductReviewAuditConcurrencyIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final String PRODUCT = "REVIEW-AUDIT-P1";
    private static final String ORDER = "AUDIT-REVIEW-1";
    private static final String BUYER = "review-audit-buyer@example.com";
    private static final String SELLER = "review-audit-seller@example.com";

    @Autowired ProductReviewService reviewService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @SpyBean AuditLogService auditLogService;
    @SpyBean ProductReviewRepository reviewRepository;

    @BeforeEach
    void setUpFixture() {
        cleanFixture();
        jdbc.update("INSERT IGNORE INTO member(email, password_hash) VALUES (?, 'test'), (?, 'test')", BUYER, SELLER);
        jdbc.update("UPDATE member SET role = 'BUYER' WHERE email = ?", BUYER);
        jdbc.update("UPDATE member SET role = 'SELLER' WHERE email = ?", SELLER);
        jdbc.update("INSERT INTO product(product_id, product_name, price, quantity, creator_id) VALUES (?, 'Review Audit Product', 100, 10, ?)",
                PRODUCT, SELLER);
        jdbc.update("INSERT INTO shop_order(order_id, member_id, price, pay_status) VALUES (?, ?, 100, 1)", ORDER, BUYER);
        jdbc.update("INSERT INTO order_detail(order_id, product_id, quantity, unit_price, item_price) VALUES (?, ?, 1, 100, 100)", ORDER,
                PRODUCT);
    }

    @AfterEach
    void cleanUpFixture() {
        cleanFixture();
    }

    private void cleanFixture() {
        jdbc.update("DELETE FROM audit_log WHERE target_type = 'REVIEW' AND target_id IN "
                + "(SELECT id FROM product_review WHERE product_id = ?)", PRODUCT);
        jdbc.update("DELETE FROM product_review WHERE product_id = ?", PRODUCT);
        jdbc.update("DELETE FROM order_detail WHERE order_id = ?", ORDER);
        jdbc.update("DELETE FROM order_status_history WHERE order_id = ?", ORDER);
        jdbc.update("DELETE FROM payment WHERE order_id = ?", ORDER);
        jdbc.update("DELETE FROM shop_order WHERE order_id = ?", ORDER);
        jdbc.update("DELETE FROM shopping_cart WHERE member_id IN (SELECT id FROM member WHERE email IN (?, ?))", BUYER, SELLER);
        jdbc.update("DELETE FROM shipping_address WHERE member_id IN (SELECT id FROM member WHERE email IN (?, ?))", BUYER, SELLER);
        jdbc.update("DELETE FROM product WHERE product_id = ?", PRODUCT);
        jdbc.update("DELETE FROM member WHERE email IN (?, ?)", BUYER, SELLER);
    }

    @Test
    void competingHideAndRestoreAuditTheCommittedPredecessor() throws Exception {
        ProductReview review = reviewService.create(PRODUCT, BUYER, request());
        CountDownLatch firstAuditReached = new CountDownLatch(1);
        CountDownLatch secondLockAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirstCommit = new CountDownLatch(1);
        AtomicBoolean firstAudit = new AtomicBoolean(true);
        AtomicBoolean reviewLockWaitObserved = new AtomicBoolean();
        AtomicLong secondConnectionId = new AtomicLong();
        doAnswer(invocation -> {
            if (firstAudit.compareAndSet(true, false)) {
                firstAuditReached.countDown();
                assertThat(releaseFirstCommit.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(auditLogService).record(anyString(), any(), any(), anyString(), any(), any());
        doAnswer(invocation -> {
            if (!firstAudit.get()) {
                secondConnectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                secondLockAttempted.countDown();
            }
            return invocation.callRealMethod();
        }).when(reviewRepository).lockById(org.mockito.ArgumentMatchers.anyLong());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProductReview> hide = pool.submit(() -> reviewService.setVisibility(review.getId(), SELLER,
                    Member.Role.SELLER, ProductReview.Visibility.HIDDEN));
            assertThat(firstAuditReached.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ProductReview> restore = pool.submit(() -> {
                return reviewService.setVisibility(review.getId(), SELLER, Member.Role.SELLER,
                        ProductReview.Visibility.VISIBLE);
            });
            // This fails against the defect: it calls nonlocking findById instead of lockById.
            assertThat(secondLockAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            reviewLockWaitObserved.set(waitForReviewLockWait(secondConnectionId.get(), review.getId()));
            releaseFirstCommit.countDown();

            assertThat(hide.get(10, TimeUnit.SECONDS).getVisibility()).isEqualTo(ProductReview.Visibility.HIDDEN);
            assertThat(restore.get(10, TimeUnit.SECONDS).getVisibility()).isEqualTo(ProductReview.Visibility.VISIBLE);
        } finally {
            releaseFirstCommit.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.queryForObject("SELECT visibility FROM product_review WHERE id = ?", String.class, review.getId()))
                .isEqualTo("VISIBLE");
        List<Map<String, Object>> audit = jdbc.queryForList("SELECT before_state, after_state FROM audit_log "
                + "WHERE action = 'REVIEW_VISIBILITY_CHANGE' AND target_id = ? ORDER BY id", String.valueOf(review.getId()));
        assertThat(audit).hasSize(2);
        assertThat(json(audit.get(0).get("before_state")).path("visibility").asText()).isEqualTo("VISIBLE");
        assertThat(json(audit.get(0).get("after_state")).path("visibility").asText()).isEqualTo("HIDDEN");
        assertThat(json(audit.get(1).get("before_state")).path("visibility").asText()).isEqualTo("HIDDEN");
        assertThat(json(audit.get(1).get("after_state")).path("visibility").asText()).isEqualTo("VISIBLE");
        assertThat(reviewLockWaitObserved.get()).isTrue();
    }

    @Test
    void auditInsertFailureRollsBackReviewVisibility() {
        ProductReview review = reviewService.create(PRODUCT, BUYER, request());
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT chk_review_audit_fail "
                + "CHECK (NOT (action = 'REVIEW_VISIBILITY_CHANGE' AND target_id = '"
                + review.getId() + "'))");
        try {
            assertThrows(DataAccessException.class, () -> reviewService.setVisibility(review.getId(), SELLER,
                    Member.Role.SELLER, ProductReview.Visibility.HIDDEN));
        } finally {
            jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT chk_review_audit_fail");
        }
        assertThat(jdbc.queryForObject("SELECT visibility FROM product_review WHERE id = ?", String.class, review.getId()))
                .isEqualTo("VISIBLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'REVIEW_VISIBILITY_CHANGE' "
                + "AND target_id = ?", Long.class, String.valueOf(review.getId()))).isZero();
    }

    private ReviewRequest request() {
        ReviewRequest request = new ReviewRequest();
        request.setRating(5);
        request.setContent("audit concurrency");
        return request;
    }

    private JsonNode json(Object value) throws Exception {
        return mapper.readTree(String.valueOf(value));
    }

    private boolean waitForReviewLockWait(long connectionId, long reviewId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            String query = """
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE = w.ENGINE
                     AND requested.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.threads requester ON requester.THREAD_ID = requested.THREAD_ID
                    WHERE requester.PROCESSLIST_ID = %d
                      AND requested.OBJECT_SCHEMA = 'esun_shop'
                      AND requested.OBJECT_NAME = 'product_review'
                      AND requested.LOCK_DATA = '%d'
                    """.formatted(connectionId, reviewId);
            // The application user intentionally cannot inspect performance_schema. The same
            // Testcontainers MySQL instance exposes this read-only observation to root instead.
            var result = MYSQL.execInContainer("mysql", "-uroot", "-ptest", "--batch", "--skip-column-names", "-e", query);
            if (result.getExitCode() != 0) throw new IllegalStateException(result.getStderr());
            if (Integer.parseInt(result.getStdout().trim()) > 0) return true;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return false;
    }
}
