package com.esun.shop.service;

import com.esun.shop.dto.CreateOrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DeadlockLoserDataAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "stock.redis.enabled=false")
class OrderRetryTest {
    @Autowired
    private OrderService orderService;

    @MockBean
    private OrderTransactionService transactionService;

    @MockBean
    private StockCacheService stockCacheService;

    @Test
    void deadlockIsRetriedOutsideTransactionAndEventuallySucceeds() {
        long retriesBefore = orderService.getRetryCount();
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId("00000000-0000-4000-8000-000000000099");
        when(transactionService.createOrder(any()))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .thenReturn("MsRETRIED");

        assertThat(orderService.createOrder(request)).isEqualTo("MsRETRIED");
        verify(transactionService, times(3)).createOrder(any());
        assertThat(orderService.getRetryCount() - retriesBefore).isEqualTo(2);
    }

    @Test
    void exhaustedDeadlockRetriesAreCappedAtThree() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId("00000000-0000-4000-8000-000000000100");
        when(transactionService.createOrder(any()))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", null));

        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(ConcurrentOrderException.class);
        verify(transactionService, times(3)).createOrder(any());
    }
}
