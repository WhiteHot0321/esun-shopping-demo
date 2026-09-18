package com.esun.shop.model;

import java.math.BigDecimal;

/** Payment transaction joined with its order while processing a provider callback. */
public class PaymentTransaction {
    private String orderId;
    private String merchantTradeNo;
    private String providerTransactionId;
    private String memberId;
    private BigDecimal amount;
    private Integer payStatus;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getMerchantTradeNo() { return merchantTradeNo; }
    public void setMerchantTradeNo(String merchantTradeNo) { this.merchantTradeNo = merchantTradeNo; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public void setProviderTransactionId(String providerTransactionId) { this.providerTransactionId = providerTransactionId; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Integer getPayStatus() { return payStatus; }
    public void setPayStatus(Integer payStatus) { this.payStatus = payStatus; }
}
