package com.esun.shop.dto;

import com.esun.shop.model.PaymentResult;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Server-to-server callback body from the payment provider. Authenticated by {@code signature}, not by a JWT. */
public class PaymentCallbackRequest {
    @NotBlank
    @Size(max = 40)
    private String merchantTradeNo;

    @NotNull
    @Positive
    @Digits(integer = 10, fraction = 2)
    private BigDecimal amount;

    @NotNull
    private PaymentResult result;

    @Size(max = 64)
    private String providerRef;

    @NotBlank
    @Size(max = 128)
    private String signature;

    public String getMerchantTradeNo() { return merchantTradeNo; }
    public void setMerchantTradeNo(String merchantTradeNo) { this.merchantTradeNo = merchantTradeNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public PaymentResult getResult() { return result; }
    public void setResult(PaymentResult result) { this.result = result; }
    public String getProviderRef() { return providerRef; }
    public void setProviderRef(String providerRef) { this.providerRef = providerRef; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
}
