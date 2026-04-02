package com.esun.shop.model;

import java.math.BigDecimal;

public class OrderDetail {
    private Long orderItemSn;
    private String orderId;
    private String productId;
    private Integer quantity;
    private BigDecimal standPrice;
    private BigDecimal itemPrice;

    public Long getOrderItemSn() {
        return orderItemSn;
    }

    public void setOrderItemSn(Long orderItemSn) {
        this.orderItemSn = orderItemSn;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getStandPrice() {
        return standPrice;
    }

    public void setStandPrice(BigDecimal standPrice) {
        this.standPrice = standPrice;
    }

    public BigDecimal getItemPrice() {
        return itemPrice;
    }

    public void setItemPrice(BigDecimal itemPrice) {
        this.itemPrice = itemPrice;
    }
}
