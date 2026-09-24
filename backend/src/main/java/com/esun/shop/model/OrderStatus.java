package com.esun.shop.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Fulfilment lifecycle of an order, persisted by name in {@code shop_order.order_status}
 * (unlike {@link PayStatus}, which is persisted by ordinal, so reordering these constants is safe).
 *
 * <pre>
 * CREATED -> CONFIRMED -> SHIPPED -> DELIVERED
 *    \           \
 *     +-----------+--> CANCELLED   (only before shipping; stock is returned)
 * </pre>
 */
public enum OrderStatus {
    CREATED,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    public Set<OrderStatus> nextStatuses() {
        return switch (this) {
            case CREATED -> EnumSet.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> EnumSet.of(SHIPPED, CANCELLED);
            case SHIPPED -> EnumSet.of(DELIVERED);
            case DELIVERED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return nextStatuses().contains(target);
    }
}
