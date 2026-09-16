package com.esun.shop.controller;

import com.esun.shop.exception.BusinessException;
import com.esun.shop.security.JwtService;
import com.esun.shop.service.OrderService;
import com.esun.shop.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrderControllerTest {
    private static final String REQUEST_ID = "b35e0f4a-9465-43ea-9c7f-91cb9f8d79d8";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private JwtService jwtService;

    @Test
    void createOrder_missingBlankOrMalformedRequestId_returns400() throws Exception {
        for (String requestId : new String[]{null, "", "not-a-uuid"}) {
            mockMvc.perform(post("/api/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload(requestId)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void createOrder_duplicateReplay_returnsOriginalOrderIdWith200() throws Exception {
        when(orderService.createOrder(any())).thenReturn("MsORIGINAL");

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(payload(REQUEST_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value("MsORIGINAL"));
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(payload(REQUEST_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value("MsORIGINAL"));
        verify(orderService, times(2)).createOrder(any());
    }

    @Test
    void createOrder_unrelatedUniqueCollision_returns409() throws Exception {
        doThrow(new DuplicateKeyException("shop_order collision")).when(orderService).createOrder(any());

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(payload(REQUEST_ID)))
                .andExpect(status().isConflict());
    }

    @Test
    void createOrder_requestIdOwnedByAnotherMember_returns409WithoutOrderId() throws Exception {
        doThrow(new BusinessException("requestId 已被其他會員使用", HttpStatus.CONFLICT))
                .when(orderService).createOrder(any());

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(payload(REQUEST_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void createOrder_exhaustedRetryHasMachineReadableCode() throws Exception {
        doThrow(new com.esun.shop.service.ConcurrentOrderException(REQUEST_ID, new RuntimeException()))
                .when(orderService).createOrder(any());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(payload(REQUEST_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_CONFLICT"));
    }

    @Test
    void createOrder_databaseErrorHasDifferentCode() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("failure"))
                .when(orderService).createOrder(any());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(payload(REQUEST_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DB_ERROR"));
    }

    private String payload(String requestId) {
        String requestIdJson = requestId == null ? "" : "\"requestId\":\"" + requestId + "\",";
        return "{" + requestIdJson + "\"memberId\":\"M001\",\"payStatus\":\"PENDING\","
                + "\"items\":[{\"productId\":\"P001\",\"quantity\":1}]}";
    }
}
