package com.esun.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderPageResponse;
import com.esun.shop.dto.OrderView;
import com.esun.shop.dto.UpdateOrderStatusRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Member;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.OrderStatusService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "訂單 Order", description = "買家訂單、賣家/管理員出貨與狀態流轉")
@RestController
public class OrderController {
    private final OrderService orderService;
    private final OrderStatusService orderStatusService;

    public OrderController(OrderService orderService, OrderStatusService orderStatusService) {
        this.orderService = orderService;
        this.orderStatusService = orderStatusService;
    }

    @Operation(summary = "建立訂單（身分取自 token；requestId 提供冪等）")
    @PostMapping("/api/orders")
    public ApiResponse<Map<String, String>> createOrder(@Valid @RequestBody CreateOrderRequest request,
            HttpServletRequest httpRequest) {
        // The authenticated principal owns the order and its address. Never trust a caller-provided
        // memberId for this authorization boundary; retain the field only for API compatibility.
        request.setMemberId((String) httpRequest.getAttribute("authenticatedEmail"));
        String orderId = orderService.createOrder(request);
        return ApiResponse.ok(Map.of("orderId", orderId));
    }

    // ---- buyer: own orders only, identity from the verified JWT ----

    @Operation(summary = "列出我的訂單（可依狀態篩選、分頁）")
    @GetMapping("/api/orders")
    public ApiResponse<OrderPageResponse> listMyOrders(@RequestParam(defaultValue = "all") String status,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       HttpServletRequest request) {
        return ApiResponse.ok(orderStatusService.listMine(principal(request), status, page, size));
    }

    @Operation(summary = "取得我的單筆訂單")
    @GetMapping("/api/orders/{orderId}")
    public ApiResponse<OrderView> getMyOrder(@PathVariable String orderId, HttpServletRequest request) {
        return ApiResponse.ok(orderStatusService.getMine(orderId, principal(request)));
    }

    @Operation(summary = "取消我的訂單（僅限允許取消的狀態）")
    @PostMapping("/api/orders/{orderId}/cancel")
    public ApiResponse<OrderView> cancelMyOrder(@PathVariable String orderId, HttpServletRequest request) {
        return ApiResponse.ok(orderStatusService.cancelMine(orderId, principal(request)));
    }

    // ---- seller / admin fulfilment ----

    @Operation(summary = "列出賣家/管理員可見的訂單（SELLER、ADMIN）")
    @GetMapping({"/api/seller/orders", "/api/admin/orders"})
    public ApiResponse<OrderPageResponse> listSellerOrders(@RequestParam(defaultValue = "all") String status,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(orderStatusService.listForSeller(principal(request), sellerRole(request), status, page, size));
    }

    @Operation(summary = "取得賣家/管理員可見的單筆訂單（SELLER、ADMIN）")
    @GetMapping({"/api/seller/orders/{orderId}", "/api/admin/orders/{orderId}"})
    public ApiResponse<OrderView> getSellerOrder(@PathVariable String orderId, HttpServletRequest request) {
        return ApiResponse.ok(orderStatusService.getForSeller(orderId, principal(request), sellerRole(request)));
    }

    @Operation(summary = "變更訂單狀態（SELLER、ADMIN；須符合狀態機）")
    @PostMapping({"/api/seller/orders/{orderId}/status", "/api/admin/orders/{orderId}/status"})
    public ApiResponse<OrderView> updateOrderStatus(@PathVariable String orderId,
                                                    @Valid @RequestBody UpdateOrderStatusRequest body,
                                                    HttpServletRequest request) {
        return ApiResponse.ok(orderStatusService.transition(orderId, body.getStatus(), principal(request),
                sellerRole(request)));
    }

    private static String principal(HttpServletRequest request) {
        Object identity = request.getAttribute("authenticatedEmail");
        if (!(identity instanceof String email) || email.isBlank()) {
            throw new BusinessException("缺少登入憑證", HttpStatus.UNAUTHORIZED);
        }
        return email;
    }

    private static Member.Role sellerRole(HttpServletRequest request) {
        Member.Role role = (Member.Role) request.getAttribute("authenticatedRole");
        if (role != Member.Role.SELLER && role != Member.Role.ADMIN) {
            throw new BusinessException("權限不足", HttpStatus.FORBIDDEN);
        }
        return role;
    }
}
