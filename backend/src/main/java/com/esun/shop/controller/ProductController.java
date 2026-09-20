package com.esun.shop.controller;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.model.Product;
import com.esun.shop.model.Member;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    public ApiResponse<Void> createProduct(@Valid @RequestBody CreateProductRequest request) {
        productService.createProduct(request);
        return ApiResponse.ok(null);
    }

    @PostMapping
    public ApiResponse<Void> createProductAuthenticated(@Valid @RequestBody CreateProductRequest request,
                                                        HttpServletRequest servletRequest) {
        Member.Role role = (Member.Role) servletRequest.getAttribute("authenticatedRole");
        if (role != Member.Role.SELLER && role != Member.Role.ADMIN) {
            throw new BusinessException("權限不足", HttpStatus.FORBIDDEN);
        }
        productService.createProduct(request, (String) servletRequest.getAttribute("authenticatedEmail"));
        return ApiResponse.ok(null);
    }

    @GetMapping("/available")
    public ApiResponse<List<Product>> getAvailableProducts() {
        return ApiResponse.ok(productService.getAvailableProducts());
    }
}
