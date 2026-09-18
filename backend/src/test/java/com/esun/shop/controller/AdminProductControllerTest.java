package com.esun.shop.controller;

import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.security.JwtService;
import com.esun.shop.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminProductControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private ProductService productService;
    @MockBean private JwtService jwtService;

    @Test
    void creationUsesAuthenticatedPrincipalRatherThanClientData() throws Exception {
        mockMvc.perform(post("/api/admin/products").requestAttr("authenticatedEmail", "owner@example.com")
                        .contentType("application/json")
                        .content("{\"productId\":\"OWNER-1\",\"productName\":\"Product\",\"price\":1.00,\"quantity\":1}"))
                .andExpect(status().isOk());
        verify(productService).createProduct(any(CreateProductRequest.class), eq("owner@example.com"));
    }

    @Test
    void ownerOnlyWriteReturns403FromService() throws Exception {
        doThrow(new BusinessException("無權管理此商品", HttpStatus.FORBIDDEN))
                .when(productService).updateOwnedProduct(eq("OWNER-1"), any(), eq("other@example.com"));
        mockMvc.perform(put("/api/admin/products/OWNER-1").requestAttr("authenticatedEmail", "other@example.com")
                        .contentType("application/json").content("{\"productName\":\"changed\",\"price\":2.00}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void restockRejectsNonPositiveAmountBeforeService() throws Exception {
        mockMvc.perform(post("/api/admin/products/OWNER-1/restock").requestAttr("authenticatedEmail", "owner@example.com")
                        .param("amount", "0"))
                .andExpect(status().isBadRequest());
    }
}
