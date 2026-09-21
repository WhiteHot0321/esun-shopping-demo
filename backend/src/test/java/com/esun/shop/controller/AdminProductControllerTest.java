package com.esun.shop.controller;

import com.esun.shop.dto.BulkProductRequest;
import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.ProductPageResponse;
import com.esun.shop.model.Member;
import com.esun.shop.security.JwtService;
import com.esun.shop.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminProductControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private ProductService productService;
    @MockBean private JwtService jwtService;

    @Test
    void sellerCreationUsesAuthenticatedPrincipalRatherThanClientOwnership() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                        .requestAttr("authenticatedEmail", "owner@example.com")
                        .requestAttr("authenticatedRole", Member.Role.SELLER)
                        .contentType("application/json")
                        .content("{\"productId\":\"OWNER-1\",\"productName\":\"Product\",\"price\":1.00,\"quantity\":1,\"creatorId\":\"attacker@example.com\"}"))
                .andExpect(status().isOk());
        verify(productService).createProduct(any(CreateProductRequest.class), eq("owner@example.com"));
    }

    @Test
    void buyerCannotAccessSellerManagementEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/products")
                        .requestAttr("authenticatedEmail", "buyer@example.com")
                        .requestAttr("authenticatedRole", Member.Role.BUYER))
                .andExpect(status().isForbidden());
        verify(productService, never()).getOwnedProducts(any());
    }

    @Test
    void restockRejectsNonPositiveAmountBeforeService() throws Exception {
        mockMvc.perform(post("/api/admin/products/OWNER-1/restock")
                        .requestAttr("authenticatedEmail", "owner@example.com")
                        .requestAttr("authenticatedRole", Member.Role.SELLER)
                        .param("amount", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchUsesPrincipalAndParametersForSellerAndDeniesBuyer() throws Exception {
        when(productService.searchOwnedProducts("owner@example.com", "tea", "active", 1, 5))
                .thenReturn(new ProductPageResponse(List.of(), 0, 1, 5));
        mockMvc.perform(get("/api/seller/products/search").param("keyword", "tea").param("status", "active")
                        .param("page", "1").param("size", "5")
                        .requestAttr("authenticatedEmail", "owner@example.com")
                        .requestAttr("authenticatedRole", Member.Role.SELLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(5));

        mockMvc.perform(get("/api/admin/products/search")
                        .requestAttr("authenticatedEmail", "buyer@example.com")
                        .requestAttr("authenticatedRole", Member.Role.BUYER))
                .andExpect(status().isForbidden());
        verify(productService, never()).searchOwnedProducts(eq("buyer@example.com"), any(), any(), anyInt(), anyInt());
    }

    @Test
    void bulkUsesPrincipalRejectsBuyerAndInvalidPayloads() throws Exception {
        String body = "{\"productIds\":[\"A\",\"B\"],\"action\":\"RESTOCK\",\"amount\":3,\"creatorId\":\"attacker\"}";
        mockMvc.perform(post("/api/seller/products/bulk").contentType("application/json").content(body)
                        .requestAttr("authenticatedEmail", "owner@example.com")
                        .requestAttr("authenticatedRole", Member.Role.SELLER))
                .andExpect(status().isOk());
        verify(productService).bulkManageOwnedProducts(any(BulkProductRequest.class), eq("owner@example.com"));

        mockMvc.perform(post("/api/admin/products/bulk").contentType("application/json").content(body)
                        .requestAttr("authenticatedEmail", "buyer@example.com")
                        .requestAttr("authenticatedRole", Member.Role.BUYER))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/products/bulk").contentType("application/json")
                        .content("{\"productIds\":[],\"action\":\"DELETE\"}")
                        .requestAttr("authenticatedEmail", "owner@example.com")
                        .requestAttr("authenticatedRole", Member.Role.SELLER))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/products/bulk").contentType("application/json")
                        .content("{\"productIds\":[\"A\"],\"action\":\"EXPLODE\"}")
                        .requestAttr("authenticatedEmail", "owner@example.com")
                        .requestAttr("authenticatedRole", Member.Role.SELLER))
                .andExpect(status().isBadRequest());
        verify(productService, times(1)).bulkManageOwnedProducts(any(), any());
    }

    @Test
    void imageUploadUsesPrincipalAndDeniesBuyer() throws Exception {
        MockMultipartFile image = new MockMultipartFile("images", "a.png", "image/png", new byte[]{1});
        when(productService.uploadOwnedProductImages(eq("P1"), any(), eq("owner@example.com")))
                .thenReturn(List.of("/uploads/products/x.png"));
        mockMvc.perform(multipart("/api/seller/products/P1/images").file(image)
                        .requestAttr("authenticatedEmail", "owner@example.com")
                        .requestAttr("authenticatedRole", Member.Role.SELLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("/uploads/products/x.png"));

        mockMvc.perform(multipart("/api/admin/products/P1/images").file(image)
                        .requestAttr("authenticatedEmail", "buyer@example.com")
                        .requestAttr("authenticatedRole", Member.Role.BUYER))
                .andExpect(status().isForbidden());
        verify(productService, times(1)).uploadOwnedProductImages(any(), any(), any());
    }
}
