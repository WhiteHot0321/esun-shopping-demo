package com.esun.shop.controller;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
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
    public ApiResponse<Map<String, String>> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        String orderId = orderService.createOrder(request);
        return ApiResponse.ok(Map.of("orderId", orderId));
    }
}
