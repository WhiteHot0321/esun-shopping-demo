package com.esun.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.CouponPageResponse;
import com.esun.shop.dto.CouponView;
import com.esun.shop.dto.CreateCouponRequest;
import com.esun.shop.dto.UpdateCouponRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Member;
import com.esun.shop.service.CouponService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Coupon management for maintainers. Every endpoint is ADMIN-only and the actor comes from the verified JWT; there
 * is intentionally no delete (orders reference coupons) — a coupon is retired by deactivating it.
 */
@Tag(name = "優惠券 Coupon", description = "僅 ADMIN 管理")
@RestController
public class CouponController {
    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @Operation(summary = "列出優惠券（ADMIN）")
    @GetMapping("/api/admin/coupons")
    public ApiResponse<CouponPageResponse> list(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(couponService.list(page, size));
    }

    @Operation(summary = "建立優惠券（ADMIN）")
    @PostMapping("/api/admin/coupons")
    public ApiResponse<CouponView> create(@Valid @RequestBody CreateCouponRequest body, HttpServletRequest request) {
        return ApiResponse.ok(couponService.create(body, requireAdmin(request), Member.Role.ADMIN));
    }

    @Operation(summary = "更新優惠券（ADMIN）")
    @PutMapping("/api/admin/coupons/{id}")
    public ApiResponse<CouponView> update(@PathVariable long id, @Valid @RequestBody UpdateCouponRequest body,
                                          HttpServletRequest request) {
        return ApiResponse.ok(couponService.update(id, body, requireAdmin(request), Member.Role.ADMIN));
    }

    @Operation(summary = "啟用／停用優惠券（ADMIN）")
    @PostMapping("/api/admin/coupons/{id}/active")
    public ApiResponse<CouponView> setActive(@PathVariable long id, @Valid @RequestBody SetActiveRequest body,
                                             HttpServletRequest request) {
        return ApiResponse.ok(couponService.setActive(id, body.active(), requireAdmin(request), Member.Role.ADMIN));
    }

    public record SetActiveRequest(@NotNull(message = "請指定啟用狀態") Boolean active) { }

    private static String requireAdmin(HttpServletRequest request) {
        if (!(request.getAttribute("authenticatedRole") instanceof Member.Role role) || role != Member.Role.ADMIN) {
            throw new BusinessException("權限不足", HttpStatus.FORBIDDEN);
        }
        Object identity = request.getAttribute("authenticatedEmail");
        if (!(identity instanceof String email) || email.isBlank()) {
            throw new BusinessException("缺少登入憑證", HttpStatus.UNAUTHORIZED);
        }
        return email;
    }
}
