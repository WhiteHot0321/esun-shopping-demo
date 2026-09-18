package com.esun.shop.controller;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.payment.PaymentForm;
import com.esun.shop.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/api/orders/{orderId}/payment-form")
    public ApiResponse<PaymentForm> createPaymentForm(@PathVariable String orderId, HttpServletRequest request) {
        Object identity = request.getAttribute("authenticatedEmail");
        if (!(identity instanceof String email) || email.isBlank()) {
            throw new BusinessException("缺少登入憑證", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        return ApiResponse.ok(paymentService.createPaymentForm(orderId, email));
    }

    /** ECPay requires the literal acknowledgement body after it has delivered a valid callback. */
    @PostMapping(value = "/api/payments/ecpay/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ecpayCallback(@RequestParam MultiValueMap<String, String> parameters) {
        try {
            Map<String, String> singleValues = new LinkedHashMap<>();
            for (Map.Entry<String, java.util.List<String>> entry : parameters.entrySet()) {
                if (entry.getValue() == null || entry.getValue().size() != 1) return rejected();
                singleValues.put(entry.getKey(), entry.getValue().get(0));
            }
            paymentService.processEcpayCallback(singleValues);
            return acknowledged();
        } catch (BusinessException ex) {
            // ECPay's server-to-server protocol requires text, not the normal API JSON envelope.
            return rejected();
        }
    }

    private ResponseEntity<String> acknowledged() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("1|OK");
    }

    private ResponseEntity<String> rejected() {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("0|ERROR");
    }
}
