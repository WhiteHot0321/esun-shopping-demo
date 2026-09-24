package com.esun.shop.dto;

import com.esun.shop.model.PaymentResult;
import jakarta.validation.constraints.NotNull;

/** Sandbox-only: the result the buyer wants the simulated provider to report for their own attempt. */
public class SandboxPaymentResultRequest {
    @NotNull
    private PaymentResult result;

    public PaymentResult getResult() { return result; }
    public void setResult(PaymentResult result) { this.result = result; }
}
