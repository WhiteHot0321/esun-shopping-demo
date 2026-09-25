package com.esun.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.RecommendationItem;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.service.RecommendationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Recommendations. The per-product list is public (aggregate-only, like the catalog); the personalised list is keyed
 * by the verified JWT identity and never accepts a member id from the caller.
 */
@Tag(name = "推薦 Recommendation", description = "由訂單歷史推導的唯讀推薦")
@RestController
public class RecommendationController {
    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @Operation(summary = "買過此商品的人也買了（公開，僅聚合資料）")
    @GetMapping("/api/products/{productId}/recommendations")
    public ApiResponse<List<RecommendationItem>> forProduct(
            @PathVariable String productId,
            @RequestParam(defaultValue = "6") int limit) {
        return ApiResponse.ok(service.forProduct(productId, limit));
    }

    @Operation(summary = "為你推薦（依登入會員購買紀錄）")
    @GetMapping("/api/recommendations")
    public ApiResponse<List<RecommendationItem>> forMember(
            @RequestParam(defaultValue = "6") int limit,
            HttpServletRequest request) {
        Object identity = request.getAttribute("authenticatedEmail");
        if (!(identity instanceof String email) || email.isBlank()) {
            throw new BusinessException("缺少登入憑證", HttpStatus.UNAUTHORIZED);
        }
        return ApiResponse.ok(service.forMember(email, limit));
    }
}
