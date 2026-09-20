package com.esun.shop.controller;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.repository.CartRepository.CartItem;
import com.esun.shop.service.CartService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CartItem>> list(HttpServletRequest request) {
        return ApiResponse.ok(service.list(email(request)));
    }

    @PostMapping("/add")
    public ApiResponse<CartItem> add(@Valid @RequestBody AddItemRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.add(email(request), body.productId().trim(), body.quantity()));
    }

    @PutMapping("/items/{itemId}")
    public ApiResponse<CartItem> update(@PathVariable long itemId,
            @Valid @RequestBody QuantityRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.update(email(request), itemId, body.quantity()));
    }

    @DeleteMapping("/items/{itemId}")
    public ApiResponse<Void> delete(@PathVariable long itemId, HttpServletRequest request) {
        service.delete(email(request), itemId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping
    public ApiResponse<Void> clear(HttpServletRequest request) {
        service.clear(email(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/checkout")
    public ApiResponse<Map<String, String>> checkout(@Valid @RequestBody CheckoutRequest body,
            HttpServletRequest request) {
        String orderId = service.checkout(email(request), body.requestId(), body.shippingAddressId());
        return ApiResponse.ok(Map.of("orderId", orderId));
    }

    private String email(HttpServletRequest request) {
        return (String) request.getAttribute("authenticatedEmail");
    }

    public record AddItemRequest(
            @NotBlank @Size(max = 20) String productId,
            @Positive(message = "購物車數量必須大於 0") int quantity) {}

    public record QuantityRequest(@Positive(message = "購物車數量必須大於 0") int quantity) {}

    public record CheckoutRequest(
            @NotBlank
            @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
            String requestId,
            @Positive(message = "收件地址編號必須大於 0") Long shippingAddressId) {}
}
