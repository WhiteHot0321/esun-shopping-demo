package com.esun.shop.controller;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderDetailResponse;
import com.esun.shop.dto.OrderPageResponse;
import com.esun.shop.service.OrderService;
import com.esun.shop.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ApiResponse<Map<String, String>> createOrder(@Valid @RequestBody CreateOrderRequest request,
                                                         HttpServletRequest servletRequest) {
        Object identity = servletRequest.getAttribute("authenticatedEmail");
        if (!(identity instanceof String email) || email.isBlank()) {
            throw new BusinessException("缺少登入憑證", HttpStatus.UNAUTHORIZED);
        }
        // The body memberId is legacy-compatible input only. It is never an authorization source.
        request.setMemberId(email);
        String orderId = orderService.createOrder(request);
        return ApiResponse.ok(Map.of("orderId", orderId));
    }

    @GetMapping
    public ApiResponse<OrderPageResponse> getOrders(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "10") int size,
                                                     @RequestParam(required = false) Integer payStatus,
                                                     HttpServletRequest servletRequest) {
        return ApiResponse.ok(orderService.getOrders(principal(servletRequest), page, size, payStatus));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderDetailResponse> getOrderDetail(@PathVariable String orderId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(orderService.getOrderDetail(orderId, principal(servletRequest)));
    }

    private static String principal(HttpServletRequest request) {
        Object identity = request.getAttribute("authenticatedEmail");
        if (!(identity instanceof String email) || email.isBlank()) {
            throw new BusinessException("缺少登入憑證", HttpStatus.UNAUTHORIZED);
        }
        return email;
    }
}
