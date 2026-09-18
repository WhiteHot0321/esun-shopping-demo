package com.esun.shop.service;

import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.OrderDetail;
import com.esun.shop.model.ShopOrder;
import com.esun.shop.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderQueryServiceTest {
    @Mock private OrderRepository orders;

    @Test
    void listUsesAuthenticatedMemberAndOptionalPayStatusForPagination() {
        OrderService service = new OrderService(null, null, orders);
        when(orders.countByMemberId("buyer@example.com", 1)).thenReturn(1L);
        when(orders.findByMemberId("buyer@example.com", 1, 10, 10)).thenReturn(List.of(order("O-1", "buyer@example.com", 1)));

        var result = service.getOrders("buyer@example.com", 1, 10, 1);

        assertThat(result.content()).extracting(item -> item.orderId()).containsExactly("O-1");
        assertThat(result.totalElements()).isEqualTo(1); assertThat(result.totalPages()).isEqualTo(1);
        verify(orders).findByMemberId("buyer@example.com", 1, 10, 10);
    }

    @Test
    void detailRejectsAnotherBuyerAndDistinguishesMissingOrder() {
        OrderService service = new OrderService(null, null, orders);
        when(orders.findOrderById("O-1")).thenReturn(Optional.of(order("O-1", "owner@example.com", 0)));
        assertThatThrownBy(() -> service.getOrderDetail("O-1", "other@example.com"))
                .isInstanceOf(BusinessException.class).satisfies(error -> assertThat(((BusinessException) error).getStatus().value()).isEqualTo(403));

        when(orders.findOrderById("MISSING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOrderDetail("MISSING", "owner@example.com"))
                .isInstanceOf(BusinessException.class).satisfies(error -> assertThat(((BusinessException) error).getStatus().value()).isEqualTo(404));
    }

    @Test
    void detailReturnsLineItemsOnlyAfterOwnerCheck() {
        OrderService service = new OrderService(null, null, orders);
        OrderDetail detail = new OrderDetail(); detail.setProductId("P001"); detail.setQuantity(2); detail.setUnitPrice(BigDecimal.TEN); detail.setItemPrice(new BigDecimal("20"));
        when(orders.findOrderById("O-1")).thenReturn(Optional.of(order("O-1", "owner@example.com", 1)));
        when(orders.findDetailsByOrderId("O-1")).thenReturn(List.of(detail));

        var result = service.getOrderDetail("O-1", "owner@example.com");
        assertThat(result.items()).hasSize(1); assertThat(result.items().get(0).productId()).isEqualTo("P001");
    }

    private static ShopOrder order(String id, String member, int payStatus) {
        ShopOrder order = new ShopOrder(); order.setOrderId(id); order.setMemberId(member); order.setPayStatus(payStatus);
        order.setPrice(BigDecimal.TEN); order.setCreatedAt(LocalDateTime.of(2026, 9, 17, 12, 0)); return order;
    }
}
