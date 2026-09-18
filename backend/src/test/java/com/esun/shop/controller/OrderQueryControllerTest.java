package com.esun.shop.controller;

import com.esun.shop.dto.OrderDetailResponse;
import com.esun.shop.dto.OrderPageResponse;
import com.esun.shop.dto.OrderSummaryResponse;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.security.JwtService;
import com.esun.shop.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrderQueryControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private OrderService orderService;
    @MockBean private JwtService jwtService;

    @Test
    void listUsesRequestPrincipalAndReturnsPagedData() throws Exception {
        var page = new OrderPageResponse(List.of(new OrderSummaryResponse("O-1", BigDecimal.TEN, 0, LocalDateTime.now())), 0, 10, 1, 1);
        when(orderService.getOrders(eq("buyer@example.com"), eq(0), eq(10), eq(null))).thenReturn(page);
        mockMvc.perform(get("/api/orders").requestAttr("authenticatedEmail", "buyer@example.com"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].orderId").value("O-1"));
    }

    @Test
    void detailMapsForbiddenAndNotFound() throws Exception {
        when(orderService.getOrderDetail("OTHER", "buyer@example.com"))
                .thenThrow(new BusinessException("無權存取此訂單", HttpStatus.FORBIDDEN));
        mockMvc.perform(get("/api/orders/OTHER").requestAttr("authenticatedEmail", "buyer@example.com"))
                .andExpect(status().isForbidden());
        when(orderService.getOrderDetail("MISSING", "buyer@example.com"))
                .thenThrow(new BusinessException("訂單不存在", HttpStatus.NOT_FOUND));
        mockMvc.perform(get("/api/orders/MISSING").requestAttr("authenticatedEmail", "buyer@example.com"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingPrincipalReturns401() throws Exception {
        mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }
}
