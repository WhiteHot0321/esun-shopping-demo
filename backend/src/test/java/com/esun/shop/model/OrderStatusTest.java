package com.esun.shop.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {
    @Test
    void onlyForwardStepsAndPreShipCancellationAreAllowed() {
        assertThat(OrderStatus.CREATED.nextStatuses()).containsExactlyInAnyOrder(OrderStatus.CONFIRMED, OrderStatus.CANCELLED);
        assertThat(OrderStatus.CONFIRMED.nextStatuses()).containsExactlyInAnyOrder(OrderStatus.SHIPPED, OrderStatus.CANCELLED);
        assertThat(OrderStatus.SHIPPED.nextStatuses()).containsExactly(OrderStatus.DELIVERED);
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
    }

    @Test
    void terminalStatesAcceptNothing() {
        for (OrderStatus terminal : new OrderStatus[]{OrderStatus.DELIVERED, OrderStatus.CANCELLED}) {
            assertThat(terminal.nextStatuses()).isEmpty();
        }
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.CREATED)).isFalse();
    }
}
