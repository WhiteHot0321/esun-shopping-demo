package com.esun.shop.model;

/** Privileged operations that are written to the append-only audit log. */
public enum AuditAction {
    PRODUCT_CREATE("PRODUCT"),
    PRODUCT_UPDATE("PRODUCT"),
    PRODUCT_DELETE("PRODUCT"),
    PRODUCT_RESTOCK("PRODUCT"),
    PRODUCT_IMAGE_UPLOAD("PRODUCT"),
    ORDER_STATUS_CHANGE("ORDER"),
    REVIEW_VISIBILITY_CHANGE("REVIEW"),
    COUPON_CREATE("COUPON"),
    COUPON_UPDATE("COUPON");

    private final String targetType;

    AuditAction(String targetType) {
        this.targetType = targetType;
    }

    public String targetType() {
        return targetType;
    }
}
