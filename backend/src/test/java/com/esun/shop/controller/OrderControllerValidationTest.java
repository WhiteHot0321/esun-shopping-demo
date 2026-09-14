package com.esun.shop.controller;

import com.esun.shop.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bean Validation on {@code CreateOrderRequest}/{@code OrderItemRequest} is the actual
 * defense against malformed input (null fields, non-positive quantity, empty item list) -
 * {@link OrderService} itself is never reached for these cases. These tests exercise that
 * boundary directly via MockMvc instead of unit-testing the service with inputs it can
 * never receive in practice.
 */
@WebMvcTest(OrderController.class)
class OrderControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    @Test
    void createOrder_negativeQuantity_returns400AndNeverCallsService() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "memberId", "M001",
                "payStatus", "PENDING",
                "items", java.util.List.of(Map.of("productId", "P001", "quantity", -1))));

        mockMvc.perform(post("/api/orders").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(orderService, never()).createOrder(any());
    }

    @Test
    void createOrder_nullQuantity_returns400AndNeverCallsService() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "memberId", "M001",
                "payStatus", "PENDING",
                "items", java.util.List.of(Map.of("productId", "P001"))));

        mockMvc.perform(post("/api/orders").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).createOrder(any());
    }

    @Test
    void createOrder_emptyItemList_returns400AndNeverCallsService() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "memberId", "M001",
                "payStatus", "PENDING",
                "items", java.util.List.of()));

        mockMvc.perform(post("/api/orders").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).createOrder(any());
    }

    @Test
    void createOrder_blankMemberId_returns400AndNeverCallsService() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "memberId", "   ",
                "payStatus", "PENDING",
                "items", java.util.List.of(Map.of("productId", "P001", "quantity", 1))));

        mockMvc.perform(post("/api/orders").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).createOrder(any());
    }

    @Test
    void createOrder_missingRequestBody_returns400() throws Exception {
        mockMvc.perform(post("/api/orders").contentType("application/json").content(""))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).createOrder(any());
    }
}
