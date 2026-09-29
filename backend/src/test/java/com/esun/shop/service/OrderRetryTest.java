package com.esun.shop.service;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.security.JwtService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "stock.redis.enabled=false")
class OrderRetryTest {
    @Autowired
    private OrderService orderService;

    @MockBean
    private OrderTransactionService transactionService;

    @MockBean
    private StockCacheService stockCacheService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JwtService jwtService;

    @LocalServerPort
    private int port;

    private double metricCount(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private CreateOrderRequest request(String requestId) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(requestId);
        return request;
    }

    @Test
    void deadlockIsRetriedOutsideTransactionAndEventuallySucceeds() {
        long retriesBefore = orderService.getRetryCount();
        double successesBefore = metricCount("shop.orders.success");
        double lockRetriesBefore = metricCount("shop.orders.lock.retry");
        double lockExhaustedBefore = metricCount("shop.orders.lock.exhausted");
        CreateOrderRequest request = request("00000000-0000-4000-8000-000000000099");
        when(transactionService.createOrderWithResult(any()))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .thenReturn(new OrderCreationResult("MsRETRIED", true));

        assertThat(orderService.createOrder(request)).isEqualTo("MsRETRIED");
        verify(transactionService, times(3)).createOrderWithResult(any());
        assertThat(orderService.getRetryCount() - retriesBefore).isEqualTo(2);
        assertThat(metricCount("shop.orders.success") - successesBefore).isEqualTo(1.0);
        assertThat(metricCount("shop.orders.lock.retry") - lockRetriesBefore).isEqualTo(2.0);
        assertThat(metricCount("shop.orders.lock.exhausted") - lockExhaustedBefore).isZero();
    }

    @Test
    void exhaustedDeadlockRetriesAreCappedAtThree() {
        double failuresBefore = metricCount("shop.orders.failure");
        double lockRetriesBefore = metricCount("shop.orders.lock.retry");
        double lockExhaustedBefore = metricCount("shop.orders.lock.exhausted");
        CreateOrderRequest request = request("00000000-0000-4000-8000-000000000100");
        when(transactionService.createOrderWithResult(any()))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", null));

        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(ConcurrentOrderException.class);
        verify(transactionService, times(3)).createOrderWithResult(any());
        assertThat(metricCount("shop.orders.failure") - failuresBefore).isEqualTo(1.0);
        assertThat(metricCount("shop.orders.lock.retry") - lockRetriesBefore).isEqualTo(2.0);
        assertThat(metricCount("shop.orders.lock.exhausted") - lockExhaustedBefore).isEqualTo(1.0);
    }

    @Test
    void newOrderAndIdempotentReplayOnlyCountTheNewSubmission() {
        double successesBefore = metricCount("shop.orders.success");
        when(transactionService.createOrderWithResult(any()))
                .thenReturn(new OrderCreationResult("MsNEW", true))
                .thenReturn(new OrderCreationResult("MsNEW", false));

        assertThat(orderService.createOrder(request("00000000-0000-4000-8000-000000000101"))).isEqualTo("MsNEW");
        assertThat(orderService.createOrder(request("00000000-0000-4000-8000-000000000101"))).isEqualTo("MsNEW");

        assertThat(metricCount("shop.orders.success") - successesBefore).isEqualTo(1.0);
    }

    @Test
    void nonRetryableFailureCountsOnceAndPreservesTheOriginalException() {
        double failuresBefore = metricCount("shop.orders.failure");
        com.esun.shop.exception.BusinessException expected = new com.esun.shop.exception.BusinessException(
                "stock unavailable", HttpStatus.CONFLICT);
        when(transactionService.createOrderWithResult(any())).thenThrow(expected);

        assertThatThrownBy(() -> orderService.createOrder(request("00000000-0000-4000-8000-000000000102")))
                .isSameAs(expected);
        assertThat(metricCount("shop.orders.failure") - failuresBefore).isEqualTo(1.0);
        verify(transactionService, times(1)).createOrderWithResult(any());
    }

    @Test
    void nonRetryableDataAccessFailureCountsOnceAndPreservesTheOriginalException() {
        double failuresBefore = metricCount("shop.orders.failure");
        DataIntegrityViolationException expected = new DataIntegrityViolationException("constraint violation");
        when(transactionService.createOrderWithResult(any())).thenThrow(expected);

        assertThatThrownBy(() -> orderService.createOrder(request("00000000-0000-4000-8000-000000000103")))
                .isSameAs(expected);
        assertThat(metricCount("shop.orders.failure") - failuresBefore).isEqualTo(1.0);
        verify(transactionService, times(1)).createOrderWithResult(any());
    }

    @Test
    void rejectedHttpTrafficIsRecordedWithOnlyStandardLowCardinalityTags() {
        assertThat(http.getForEntity("http://localhost:" + port + "/actuator/health/liveness", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Timer timer = meterRegistry.find("http.server.requests").tag("status", "401").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThan(0);
        assertThat(timer.getId().getTags().stream().map(Tag::getKey))
                .contains("method", "status", "uri")
                .doesNotContain("orderId", "memberId", "email", "requestId", "url");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken("metrics-review@example.com"));
        ResponseEntity<String> metrics = http.exchange(
                "http://localhost:" + port + "/actuator/metrics/http.server.requests",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody())
                .contains("\"name\":\"http.server.requests\"", "\"availableTags\"",
                        "\"method\"", "\"status\"", "\"uri\"")
                .doesNotContain("orderId", "memberId", "email", "requestId", "url");
    }
}
