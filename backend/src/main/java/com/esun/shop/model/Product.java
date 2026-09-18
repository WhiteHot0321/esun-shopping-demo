package com.esun.shop.model;

import java.math.BigDecimal;

public class Product {
    private String productId;
    private String productName;
    private BigDecimal price;
    private Integer quantity;
    private String creatorId;
    private java.time.LocalDateTime deletedAt;

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getCreatorId() { return creatorId; }

    public void setCreatorId(String creatorId) { this.creatorId = creatorId; }

    public java.time.LocalDateTime getDeletedAt() { return deletedAt; }

    public void setDeletedAt(java.time.LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
