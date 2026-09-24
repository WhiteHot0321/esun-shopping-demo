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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
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
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("merchantTradeNo", body.getMerchantTradeNo());
        parameters.put("amount", body.getAmount().toPlainString());
        parameters.put("result", body.getResult().name());
        if (body.getProviderRef() != null) parameters.put("providerRef", body.getProviderRef());
        parameters.put("signature", body.getSignature());
        return ApiResponse.ok(Map.of("outcome", callbackService.handle(parameters).name()));
    }

    /**
     * ECPay's server-to-server callback: form-encoded, authenticated by CheckMacValue, and answered with the literal
     * text {@code 1|OK} (anything else makes ECPay retry). Duplicates and refund-flagged payments are acknowledged too:
     * the money movement is already recorded, retrying would change nothing.
     */
    @PostMapping(value = "/api/payments/ecpay/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ecpayCallback(@RequestParam MultiValueMap<String, String> formParameters) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : formParameters.entrySet()) {
            if (entry.getValue() == null || entry.getValue().size() != 1) return ecpayRejected();
            parameters.put(entry.getKey(), entry.getValue().get(0));
        }
        try {
            callbackService.handle(parameters);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("1|OK");
        } catch (BusinessException ex) {
            return ecpayRejected();
        }
    }

    private static ResponseEntity<String> ecpayRejected() {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("0|ERROR");
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
