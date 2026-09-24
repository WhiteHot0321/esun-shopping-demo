package com.esun.shop.service;

import com.esun.shop.model.PaymentResult;

import java.math.BigDecimal;

/** Callback fields that are trustworthy because the gateway authenticated the whole message. */
public record VerifiedPaymentCallback(String merchantTradeNo, BigDecimal amount, PaymentResult result,
                                      String providerRef) { }
