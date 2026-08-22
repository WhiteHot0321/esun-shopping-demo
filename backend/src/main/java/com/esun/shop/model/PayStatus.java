package com.esun.shop.model;

/**
 * Payment status of an order.
 * Ordinal values (PENDING=0, PAID=1, SHIPPED=2) are persisted directly into the
 * {@code shop_order.pay_status} TINYINT column, so the declaration order below must
 * not change without a corresponding DB migration.
 */
public enum PayStatus {
    PENDING,
    PAID,
    SHIPPED
}
