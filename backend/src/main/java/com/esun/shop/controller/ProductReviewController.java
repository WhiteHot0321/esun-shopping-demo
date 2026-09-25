package com.esun.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "商品評論 Review", description = "買家評論與賣家審核")
@RestController
public class ProductReviewController {
    private final ProductReviewService reviewService;

    public ProductReviewController(ProductReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(summary = "列出商品評論（公開）")
    @GetMapping("/api/products/{productId}/reviews")
    public ApiResponse<ReviewPageResponse> getReviews(
            @PathVariable String productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "newest") String sort) {
        return ApiResponse.ok(reviewService.getVisible(productId, page, size, sort));
    }

    @Operation(summary = "新增商品評論（需已購買）")
    @PostMapping("/api/products/{productId}/reviews")
    public ResponseEntity<ApiResponse<ProductReview>> createReview(
            @PathVariable String productId, @Valid @RequestBody ReviewRequest request,
            HttpServletRequest servletRequest) {
        ProductReview review = reviewService.create(productId, email(servletRequest), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(review));
    }

    @Operation(summary = "取得我對某商品的評論")
    @GetMapping("/api/reviews/mine/{productId}")
    public ApiResponse<ProductReview> getMine(@PathVariable String productId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewService.getMine(productId, email(servletRequest)));
    }

    @Operation(summary = "修改我的評論")
    @PutMapping("/api/reviews/{reviewId}")
    public ApiResponse<ProductReview> updateReview(
            @PathVariable long reviewId, @Valid @RequestBody ReviewRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewService.update(reviewId, email(servletRequest), request));
    }

    @Operation(summary = "刪除我的評論")
    @DeleteMapping("/api/reviews/{reviewId}")
    public ResponseEntity<Void> deleteReview(@PathVariable long reviewId, HttpServletRequest servletRequest) {
        reviewService.delete(reviewId, email(servletRequest));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "列出賣家商品的評論（SELLER、ADMIN）")
    @GetMapping("/api/seller/reviews")
    public ApiResponse<List<ProductReview>> getSellerReviews(
            @RequestParam(required = false) String productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewService.getForSeller(
                email(servletRequest), role(servletRequest), productId, page, size));
    }

    @Operation(summary = "隱藏評論（SELLER、ADMIN）")
    @PostMapping("/api/seller/reviews/{reviewId}/hide")
    public ApiResponse<ProductReview> hideReview(@PathVariable long reviewId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewService.setVisibility(reviewId, email(servletRequest), role(servletRequest),
                ProductReview.Visibility.HIDDEN));
    }

    @Operation(summary = "還原被隱藏的評論（SELLER、ADMIN）")
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
