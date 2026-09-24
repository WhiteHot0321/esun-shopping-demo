package com.esun.shop.controller;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.PaymentCallbackRequest;
import com.esun.shop.dto.PaymentView;
import com.esun.shop.dto.SandboxPaymentResultRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.service.PaymentCallbackService;
import com.esun.shop.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PaymentController {
    private final PaymentService paymentService;
    private final PaymentCallbackService callbackService;

    public PaymentController(PaymentService paymentService, PaymentCallbackService callbackService) {
        this.paymentService = paymentService;
        this.callbackService = callbackService;
    }

    /** Buyer: open (or resume) the payment attempt for their own order. */
    @PostMapping("/api/orders/{orderId}/payment")
    public ApiResponse<PaymentView> startPayment(@PathVariable String orderId, HttpServletRequest request) {
        return ApiResponse.ok(paymentService.start(orderId, principal(request)));
    }

    /**
     * Payment provider webhook. Public route (no JWT): authenticity comes solely from the signature, which
     * {@link PaymentCallbackService} verifies before anything else.
     */
    @PostMapping("/api/payments/callback")
    public ApiResponse<Map<String, String>> callback(@Valid @RequestBody PaymentCallbackRequest body) {
        PaymentCallbackService.Outcome outcome = callbackService.handle(body.getMerchantTradeNo(), body.getAmount(),
                body.getResult(), body.getProviderRef(), body.getSignature());
        return ApiResponse.ok(Map.of("outcome", outcome.name()));
    }

    /** Sandbox only (404 otherwise): complete the caller's own attempt as the simulated provider would. */
    @PostMapping("/api/payments/{merchantTradeNo}/sandbox-result")
    public ApiResponse<PaymentView> sandboxResult(@PathVariable String merchantTradeNo,
                                                  @Valid @RequestBody SandboxPaymentResultRequest body,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(paymentService.simulate(merchantTradeNo, principal(request), body.getResult()));
    }

    private static String principal(HttpServletRequest request) {
        Object identity = request.getAttribute("authenticatedEmail");
        if (!(identity instanceof String email) || email.isBlank()) {
            throw new BusinessException("缺少登入憑證", HttpStatus.UNAUTHORIZED);
        }
        return email;
    }
}
