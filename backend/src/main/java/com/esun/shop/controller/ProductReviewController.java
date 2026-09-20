package com.esun.shop.controller;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.ReviewPageResponse;
import com.esun.shop.dto.ReviewRequest;
import com.esun.shop.model.Member;
import com.esun.shop.model.ProductReview;
import com.esun.shop.service.ProductReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ProductReviewController {
    private final ProductReviewService reviewService;

    public ProductReviewController(ProductReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/api/products/{productId}/reviews")
    public ApiResponse<ReviewPageResponse> getReviews(
            @PathVariable String productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "newest") String sort) {
        return ApiResponse.ok(reviewService.getVisible(productId, page, size, sort));
    }

    @PostMapping("/api/products/{productId}/reviews")
    public ResponseEntity<ApiResponse<ProductReview>> createReview(
            @PathVariable String productId, @Valid @RequestBody ReviewRequest request,
            HttpServletRequest servletRequest) {
        ProductReview review = reviewService.create(productId, email(servletRequest), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(review));
    }

    @GetMapping("/api/reviews/mine/{productId}")
    public ApiResponse<ProductReview> getMine(@PathVariable String productId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewService.getMine(productId, email(servletRequest)));
    }

    @PutMapping("/api/reviews/{reviewId}")
    public ApiResponse<ProductReview> updateReview(
            @PathVariable long reviewId, @Valid @RequestBody ReviewRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewService.update(reviewId, email(servletRequest), request));
    }

    @DeleteMapping("/api/reviews/{reviewId}")
    public ResponseEntity<Void> deleteReview(@PathVariable long reviewId, HttpServletRequest servletRequest) {
        reviewService.delete(reviewId, email(servletRequest));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/seller/reviews")
    public ApiResponse<List<ProductReview>> getSellerReviews(
            @RequestParam(required = false) String productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewService.getForSeller(
                email(servletRequest), role(servletRequest), productId, page, size));
    }

    @PostMapping("/api/seller/reviews/{reviewId}/hide")
    public ApiResponse<ProductReview> hideReview(@PathVariable long reviewId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewService.setVisibility(reviewId, email(servletRequest), role(servletRequest),
                ProductReview.Visibility.HIDDEN));
    }

    @PostMapping("/api/seller/reviews/{reviewId}/restore")
    public ApiResponse<ProductReview> restoreReview(@PathVariable long reviewId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewService.setVisibility(reviewId, email(servletRequest), role(servletRequest),
                ProductReview.Visibility.VISIBLE));
    }

    private static String email(HttpServletRequest request) {
        return (String) request.getAttribute("authenticatedEmail");
    }

    private static Member.Role role(HttpServletRequest request) {
        return (Member.Role) request.getAttribute("authenticatedRole");
    }
}
