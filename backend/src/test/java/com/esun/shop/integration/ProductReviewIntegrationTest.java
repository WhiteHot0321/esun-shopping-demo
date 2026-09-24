package com.esun.shop.integration;

import com.esun.shop.dto.ReviewRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Member;
import com.esun.shop.model.ProductReview;
import com.esun.shop.service.ProductReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductReviewIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final String PRODUCT = "REVIEW-P1";
    private static final String BUYER = "review-buyer@example.com";
    private static final String OTHER = "review-other@example.com";
    private static final String SELLER = "review-seller@example.com";
    private static final String OTHER_SELLER = "review-other-seller@example.com";

    @Autowired ProductReviewService reviewService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUpReviewFixture() {
        jdbc.update("DELETE FROM product_review WHERE product_id = ?", PRODUCT);
        jdbc.update("DELETE FROM order_detail WHERE product_id = ?", PRODUCT);
        jdbc.update("DELETE FROM order_status_history WHERE order_id LIKE 'REVIEW-%'");
        jdbc.update("DELETE FROM shop_order WHERE order_id LIKE 'REVIEW-%'");
        jdbc.update("DELETE FROM product WHERE product_id = ?", PRODUCT);
        for (String email : List.of(BUYER, OTHER, SELLER, OTHER_SELLER)) {
            jdbc.update("INSERT IGNORE INTO member(email, password_hash) VALUES (?, 'test')", email);
        }
        jdbc.update("UPDATE member SET role = 'BUYER' WHERE email IN (?, ?)", BUYER, OTHER);
        jdbc.update("UPDATE member SET role = 'SELLER' WHERE email IN (?, ?)", SELLER, OTHER_SELLER);
        jdbc.update("INSERT INTO product(product_id, product_name, price, quantity, creator_id) VALUES (?, 'Review Product', 100, 10, ?)",
                PRODUCT, SELLER);
    }

    @Test
    void verifiedBuyerOwnsCrudAndDuplicateInvariant() {
        BusinessException noPurchase = assertThrows(BusinessException.class,
                () -> reviewService.create(PRODUCT, BUYER, request(5, "great")));
        assertEquals(HttpStatus.FORBIDDEN, noPurchase.getStatus());

        purchase("REVIEW-ORDER-1", BUYER);
        ProductReview created = reviewService.create(PRODUCT, BUYER, request(5, "great"));
        assertNotNull(created.getId());
        assertEquals(new BigDecimal("5.00"), reviewService.getVisible(PRODUCT, 0, 10, "newest").averageRating());

        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> reviewService.create(PRODUCT, BUYER, request(4, "again")));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatus());

        BusinessException otherUpdate = assertThrows(BusinessException.class,
                () -> reviewService.update(created.getId(), OTHER, request(1, "tampered")));
        assertEquals(HttpStatus.FORBIDDEN, otherUpdate.getStatus());

        ProductReview updated = reviewService.update(created.getId(), BUYER, request(4, "updated"));
        assertEquals(4, updated.getRating());
        assertEquals("updated", updated.getContent());
        reviewService.delete(created.getId(), BUYER);
        assertEquals(0, reviewService.getVisible(PRODUCT, 0, 10, "newest").reviewCount());
    }

    @Test
    void concurrentDuplicateCreateHasExactlyOneWinner() throws Exception {
        purchase("REVIEW-ORDER-2", BUYER);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var calls = List.of(1, 2).stream().map(i -> pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    reviewService.create(PRODUCT, BUYER, request(i + 2, "concurrent " + i));
                    return "created";
                } catch (BusinessException ex) {
                    return ex.getStatus().name();
                }
            })).toList();
            ready.await();
            start.countDown();
            List<String> results = List.of(calls.get(0).get(), calls.get(1).get());
            assertEquals(1, results.stream().filter("created"::equals).count());
            assertEquals(1, results.stream().filter("CONFLICT"::equals).count());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_review WHERE product_id = ?", Long.class, PRODUCT));
    }

    @Test
    void sellerCanOnlyModerateOwnedProductAndHiddenReviewsLeaveAggregates() {
        purchase("REVIEW-ORDER-3", BUYER);
        ProductReview review = reviewService.create(PRODUCT, BUYER, request(5, "visible"));

        BusinessException buyerDenied = assertThrows(BusinessException.class,
                () -> reviewService.setVisibility(review.getId(), BUYER, Member.Role.BUYER,
                        ProductReview.Visibility.HIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, buyerDenied.getStatus());

        BusinessException crossSeller = assertThrows(BusinessException.class,
                () -> reviewService.setVisibility(review.getId(), OTHER_SELLER, Member.Role.SELLER,
                        ProductReview.Visibility.HIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, crossSeller.getStatus());

        ProductReview hidden = reviewService.setVisibility(review.getId(), SELLER, Member.Role.SELLER,
                ProductReview.Visibility.HIDDEN);
        assertEquals(ProductReview.Visibility.HIDDEN, hidden.getVisibility());
        assertEquals(0, reviewService.getVisible(PRODUCT, 0, 10, "newest").reviewCount());
        assertEquals(1, reviewService.getForSeller(SELLER, Member.Role.SELLER, PRODUCT, 0, 20).size());

        reviewService.setVisibility(review.getId(), SELLER, Member.Role.SELLER,
                ProductReview.Visibility.VISIBLE);
        assertEquals(1, reviewService.getVisible(PRODUCT, 0, 10, "rating-high").reviewCount());
    }

    private void purchase(String orderId, String email) {
        jdbc.update("INSERT INTO shop_order(order_id, member_id, price, pay_status) VALUES (?, ?, 100, 1)",
                orderId, email);
        jdbc.update("INSERT INTO order_detail(order_id, product_id, quantity, unit_price, item_price) VALUES (?, ?, 1, 100, 100)",
                orderId, PRODUCT);
    }

    private ReviewRequest request(int rating, String content) {
        ReviewRequest request = new ReviewRequest();
        request.setRating(rating);
        request.setContent(content);
        return request;
    }
}
