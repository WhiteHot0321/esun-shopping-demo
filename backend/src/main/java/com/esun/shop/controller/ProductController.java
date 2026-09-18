package com.esun.shop.controller;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.UpdateProductRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Product;
import com.esun.shop.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /** Compatibility route for the existing authenticated client; it has the same ownership rule as admin creation. */
    @PostMapping("/api/products")
    public ApiResponse<Void> createProduct(@Valid @RequestBody CreateProductRequest request, HttpServletRequest servletRequest) {
        productService.createProduct(request, principal(servletRequest));
        return ApiResponse.ok(null);
    }

    @PostMapping("/api/admin/products")
    public ApiResponse<Void> createAdminProduct(@Valid @RequestBody CreateProductRequest request, HttpServletRequest servletRequest) {
        productService.createProduct(request, principal(servletRequest));
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/admin/products")
    public ApiResponse<List<Product>> getOwnedProducts(HttpServletRequest servletRequest) {
        return ApiResponse.ok(productService.getOwnedProducts(principal(servletRequest)));
    }

    @GetMapping("/api/admin/products/{productId}")
    public ApiResponse<Product> getOwnedProduct(@PathVariable String productId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(productService.getOwnedProduct(productId, principal(servletRequest)));
    }

    @PutMapping("/api/admin/products/{productId}")
    public ApiResponse<Void> updateOwnedProduct(@PathVariable String productId, @Valid @RequestBody UpdateProductRequest request,
                                                HttpServletRequest servletRequest) {
        productService.updateOwnedProduct(productId, request, principal(servletRequest));
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/admin/products/{productId}")
    public ApiResponse<Void> deleteOwnedProduct(@PathVariable String productId, HttpServletRequest servletRequest) {
        productService.deleteOwnedProduct(productId, principal(servletRequest));
        return ApiResponse.ok(null);
    }

    @PostMapping("/api/admin/products/{productId}/restock")
    public ApiResponse<Void> restockOwnedProduct(@PathVariable String productId, @RequestParam int amount,
                                                 HttpServletRequest servletRequest) {
        if (amount <= 0) throw new BusinessException("補貨量必須大於零", HttpStatus.BAD_REQUEST);
        productService.restockOwnedProduct(productId, amount, principal(servletRequest));
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/products/available")
    public ApiResponse<List<Product>> getAvailableProducts() {
        return ApiResponse.ok(productService.getAvailableProducts());
    }

    private static String principal(HttpServletRequest request) {
        Object identity = request.getAttribute("authenticatedEmail");
        if (!(identity instanceof String email) || email.isBlank()) {
            throw new BusinessException("缺少登入憑證", HttpStatus.UNAUTHORIZED);
        }
        return email;
    }
}
