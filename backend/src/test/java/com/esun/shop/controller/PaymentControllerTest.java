package com.esun.shop.controller;

import com.esun.shop.exception.BusinessException;
import com.esun.shop.security.JwtService;
import com.esun.shop.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private PaymentService paymentService;
    @MockBean private JwtService jwtService;

    @Test
    void ecpayCallback_validNotificationAcknowledgesWithPlainText() throws Exception {
        mockMvc.perform(post("/api/payments/ecpay/callback")
                        .contentType("application/x-www-form-urlencoded")
                        .content("MerchantTradeNo=E0123456789ABCDEF012&CheckMacValue=signature"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("1|OK"));
        verify(paymentService).processEcpayCallback(any());
    }

    @Test
    void ecpayCallback_rejectionStillUsesEcpayPlainTextProtocol() throws Exception {
        doThrow(new BusinessException("付款通知驗證失敗", HttpStatus.BAD_REQUEST))
                .when(paymentService).processEcpayCallback(any());

        mockMvc.perform(post("/api/payments/ecpay/callback")
                        .contentType("application/x-www-form-urlencoded")
                        .content("MerchantTradeNo=E0123456789ABCDEF012&CheckMacValue=bad"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("0|ERROR"));
    }
}
