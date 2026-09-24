package com.esun.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Configuration is deliberately environment-backed: payment secrets must never be committed. */
@Component
public class EcpayProperties {
    private final String merchantId;
    private final String hashKey;
    private final String hashIv;
    private final String paymentUrl;
    private final String callbackUrl;
    private final String returnUrl;

    public EcpayProperties(
            @Value("${ecpay.merchant-id:}") String merchantId,
            @Value("${ecpay.hash-key:}") String hashKey,
            @Value("${ecpay.hash-iv:}") String hashIv,
            @Value("${ecpay.payment-url:}") String paymentUrl,
            @Value("${ecpay.callback-url:}") String callbackUrl,
            @Value("${ecpay.return-url:}") String returnUrl) {
        this.merchantId = merchantId;
        this.hashKey = hashKey;
        this.hashIv = hashIv;
        this.paymentUrl = paymentUrl;
        this.callbackUrl = callbackUrl;
        this.returnUrl = returnUrl;
    }

    public String getMerchantId() { return merchantId; }
    public String getHashKey() { return hashKey; }
    public String getHashIv() { return hashIv; }
    public String getPaymentUrl() { return paymentUrl; }
    public String getCallbackUrl() { return callbackUrl; }
    public String getReturnUrl() { return returnUrl; }

    public boolean isComplete() {
        return notBlank(merchantId) && notBlank(hashKey) && notBlank(hashIv)
                && notBlank(paymentUrl) && notBlank(callbackUrl) && notBlank(returnUrl);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
