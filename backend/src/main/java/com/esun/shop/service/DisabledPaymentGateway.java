package com.esun.shop.service;

import com.esun.shop.dto.PaymentRedirect;
import com.esun.shop.model.PaymentResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/** The default ({@code payment.provider=none}): no payment can start and every callback is refused. */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "none", matchIfMissing = true)
public class DisabledPaymentGateway implements PaymentGateway {
    @Override
    public String providerName() {
        return "none";
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public boolean supportsSimulation() {
        return false;
    }

    @Override
    public String newMerchantTradeNo() {
        throw new UnsupportedOperationException("payment provider is disabled");
    }

    @Override
    public Optional<PaymentRedirect> checkout(String merchantTradeNo, BigDecimal amount, String description) {
        throw new UnsupportedOperationException("payment provider is disabled");
    }

    @Override
    public Optional<VerifiedPaymentCallback> verifyCallback(Map<String, String> parameters) {
        return Optional.empty();
    }

    @Override
    public Map<String, String> simulatedCallback(String merchantTradeNo, BigDecimal amount, PaymentResult result,
                                                 String providerRef) {
        throw new UnsupportedOperationException("payment provider is disabled");
    }
}
