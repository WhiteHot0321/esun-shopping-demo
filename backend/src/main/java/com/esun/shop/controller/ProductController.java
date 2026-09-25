package com.esun.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.UpdateProductRequest;
import com.esun.shop.dto.BulkProductRequest;
import com.esun.shop.dto.ProductPageResponse;
import com.esun.shop.model.Product;
import com.esun.shop.model.Member;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "商品 Product", description = "公開目錄與賣家/管理員商品管理")
@RestController
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    public ApiResponse<Void> createProduct(@Valid @RequestBody CreateProductRequest request) {
        productService.createProduct(request);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "建立商品（SELLER、ADMIN）")
    @PostMapping("/api/products")
    public ApiResponse<Void> createProductAuthenticated(@Valid @RequestBody CreateProductRequest request,
                                                        HttpServletRequest servletRequest) {
        Member.Role role = (Member.Role) servletRequest.getAttribute("authenticatedRole");
        if (role != Member.Role.SELLER && role != Member.Role.ADMIN) {
            throw new BusinessException("權限不足", HttpStatus.FORBIDDEN);
        }
        productService.createProduct(request, (String) servletRequest.getAttribute("authenticatedEmail"));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "列出可售商品（公開）")
    @GetMapping("/api/products/available")
    public ApiResponse<List<Product>> getAvailableProducts() {
        return ApiResponse.ok(productService.getAvailableProducts());
    }

    @Operation(summary = "建立商品（賣家後台，SELLER、ADMIN）")
    @PostMapping("/api/admin/products")
    public ApiResponse<Void> createAdminProduct(@Valid @RequestBody CreateProductRequest request,
                                                HttpServletRequest servletRequest) {
        requireSellerRole(servletRequest);
        productService.createProduct(request, principal(servletRequest));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "列出自己的商品（SELLER、ADMIN）")
    @GetMapping("/api/admin/products")
    public ApiResponse<List<Product>> getOwnedProducts(HttpServletRequest servletRequest) {
        requireSellerRole(servletRequest);
        return ApiResponse.ok(productService.getOwnedProducts(principal(servletRequest)));
    }

    @Operation(summary = "搜尋自己的商品（分頁，SELLER、ADMIN）")
    @GetMapping({"/api/admin/products/search", "/api/seller/products/search"})
    public ApiResponse<ProductPageResponse> searchOwnedProducts(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest servletRequest) {
        requireSellerRole(servletRequest);
        return ApiResponse.ok(productService.searchOwnedProducts(
                principal(servletRequest), keyword, status, page, size));
    }

    @Operation(summary = "取得自己的單一商品（SELLER、ADMIN）")
    @GetMapping("/api/admin/products/{productId}")
    public ApiResponse<Product> getOwnedProduct(@PathVariable String productId, HttpServletRequest servletRequest) {
        requireSellerRole(servletRequest);
        return ApiResponse.ok(productService.getOwnedProduct(productId, principal(servletRequest)));
    }

    @Operation(summary = "更新自己的商品（SELLER、ADMIN）")
    @PutMapping("/api/admin/products/{productId}")
    public ApiResponse<Void> updateOwnedProduct(@PathVariable String productId,
                                                @Valid @RequestBody UpdateProductRequest request,
                                                HttpServletRequest servletRequest) {
        requireSellerRole(servletRequest);
        productService.updateOwnedProduct(productId, request, principal(servletRequest));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "軟刪除自己的商品（SELLER、ADMIN）")
    @DeleteMapping("/api/admin/products/{productId}")
    public ApiResponse<Void> deleteOwnedProduct(@PathVariable String productId, HttpServletRequest servletRequest) {
        requireSellerRole(servletRequest);
        productService.deleteOwnedProduct(productId, principal(servletRequest));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "補貨（SELLER、ADMIN）")
    @PostMapping("/api/admin/products/{productId}/restock")
    public ApiResponse<Void> restockOwnedProduct(@PathVariable String productId, @RequestParam int amount,
                                                 HttpServletRequest servletRequest) {
        requireSellerRole(servletRequest);
        if (amount <= 0) throw new BusinessException("補貨量必須大於零", HttpStatus.BAD_REQUEST);
        productService.restockOwnedProduct(productId, amount, principal(servletRequest));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "批量上架／下架／刪除商品（SELLER、ADMIN）")
    @PostMapping({"/api/admin/products/bulk", "/api/seller/products/bulk"})
    public ApiResponse<Void> bulkManageOwnedProducts(@Valid @RequestBody BulkProductRequest request,
                                                     HttpServletRequest servletRequest) {
        requireSellerRole(servletRequest);
        productService.bulkManageOwnedProducts(request, principal(servletRequest));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "上傳商品圖片 multipart（SELLER、ADMIN）")
    @PostMapping(value = {"/api/admin/products/{productId}/images", "/api/seller/products/{productId}/images"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<String>> uploadOwnedProductImages(@PathVariable String productId,
                                                               @RequestPart("images") MultipartFile[] images,
                                                               HttpServletRequest servletRequest) {
        requireSellerRole(servletRequest);
        return ApiResponse.ok(productService.uploadOwnedProductImages(
                productId, images, principal(servletRequest)));
    }

    private static String principal(HttpServletRequest request) {
        Object identity = request.getAttribute("authenticatedEmail");
        if (!(identity instanceof String email) || email.isBlank()) {
            throw new BusinessException("缺少登入憑證", HttpStatus.UNAUTHORIZED);
        }
        return email;
    }

    private static void requireSellerRole(HttpServletRequest request) {
        Member.Role role = (Member.Role) request.getAttribute("authenticatedRole");
        if (role != Member.Role.SELLER && role != Member.Role.ADMIN) {
            throw new BusinessException("權限不足", HttpStatus.FORBIDDEN);
        }
    }
}
