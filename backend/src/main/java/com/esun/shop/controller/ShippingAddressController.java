package com.esun.shop.controller;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.repository.ShippingAddressRepository.ShippingAddress;
import com.esun.shop.service.ShippingAddressService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

@RestController
@RequestMapping("/api/member/addresses")
public class ShippingAddressController {
    private final ShippingAddressService service;

    public ShippingAddressController(ShippingAddressService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ShippingAddress>> list(HttpServletRequest request) {
        return ApiResponse.ok(service.list(email(request)));
    }

    @PostMapping
    public ApiResponse<ShippingAddress> create(@Valid @RequestBody AddressRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(service.create(email(request), body.toInput()));
    }

    @PutMapping("/{id}")
    public ApiResponse<ShippingAddress> update(@PathVariable long id,
            @Valid @RequestBody AddressRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.update(id, email(request), body.toInput()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id, HttpServletRequest request) {
        service.delete(id, email(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/set-default")
    public ApiResponse<ShippingAddress> setDefault(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.setDefault(id, email(request)));
    }

    private String email(HttpServletRequest request) {
        return (String) request.getAttribute("authenticatedEmail");
    }

    public record AddressRequest(
            @NotBlank(message = "地址標籤不可空白") @Size(max = 50) String label,
            @NotBlank(message = "收件人不可空白") @Size(max = 100) String receiverName,
            @NotBlank(message = "電話不可空白") @Size(max = 30)
            @Pattern(regexp = "^[0-9+()\\-\\s]+$", message = "電話格式不正確") String phone,
            @Size(max = 10) @Pattern(regexp = "^[0-9A-Za-z\\-]*$", message = "郵遞區號格式不正確") String postalCode,
            @NotBlank(message = "地址不可空白") @Size(max = 255) String address,
            Boolean isDefault) {
        ShippingAddressService.AddressInput toInput() {
            return new ShippingAddressService.AddressInput(
                    label.trim(), receiverName.trim(), phone.trim(),
                    postalCode == null ? null : postalCode.trim(), address.trim(), isDefault);
        }
    }
}
