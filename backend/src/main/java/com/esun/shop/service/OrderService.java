package com.esun.shop.service;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.repository.OrderRepository;
import com.esun.shop.repository.ProductRepository;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import com.esun.shop.exception.BusinessException;

import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.support.RetrySynchronizationManager;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final OrderTransactionService transactionService;
    private final AtomicLong retryCount = new AtomicLong();
    private final StockCacheService stockCacheService;

    @Autowired
    public OrderService(OrderTransactionService transactionService, StockCacheService stockCacheService) {
        this.transactionService = transactionService;
        this.stockCacheService = stockCacheService;
    }

    public OrderService(OrderTransactionService transactionService) {
        this.transactionService = transactionService;
        this.stockCacheService = null;
    }

    /** Compatibility constructor used by isolated service unit tests. */
    public OrderService(ProductRepository productRepository, OrderRepository orderRepository) {
        this(new OrderTransactionService(productRepository, orderRepository));
    }

    @Retryable(retryFor = {CannotAcquireLockException.class, DeadlockLoserDataAccessException.class},
            maxAttemptsExpression = "#{T(java.lang.Math).max(1, T(java.lang.Math).min(3, ${order.retry.max-attempts:3}))}",
            backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 200, random = true))
    public String createOrder(CreateOrderRequest request) {
        var retryContext = RetrySynchronizationManager.getContext();
        if (retryContext != null && retryContext.getRetryCount() > 0) {
            retryCount.incrementAndGet();
            log.info("Order retry requestId={} attempt={}", request.getRequestId(), retryContext.getRetryCount() + 1);
        }
        if (stockCacheService == null || !stockCacheService.isEnabled()) {
            return transactionService.createOrder(request);
        }
        List<com.esun.shop.dto.OrderItemRequest> items = request.getItems();
        var reservation = new java.util.concurrent.atomic.AtomicReference<>(StockCacheService.Reservation.BYPASSED);
        try {
            OrderCreationResult result = transactionService.createOrderWithStock(request, () -> {
                reservation.set(stockCacheService.tryDecrease(items));
                if (reservation.get() == StockCacheService.Reservation.INSUFFICIENT) {
                    throw new BusinessException("商品庫存不足", org.springframework.http.HttpStatus.CONFLICT);
                }
            });
            return result.orderId();
        } catch (RuntimeException ex) {
            // The transactional proxy has finished rolling back before compensation.
            if (reservation.get() == StockCacheService.Reservation.RESERVED) stockCacheService.compensate(items);
            throw ex;
        }
    }

    @Recover
    public String recover(CannotAcquireLockException cause, CreateOrderRequest request) {
        return recoverLockContention(cause, request);
    }

    @Recover
    public String recover(DeadlockLoserDataAccessException cause, CreateOrderRequest request) {
        return recoverLockContention(cause, request);
    }

    @Recover
    public String recover(DataAccessException cause, CreateOrderRequest request) {
        throw cause;
    }

    @Recover
    public String recover(BusinessException cause, CreateOrderRequest request) {
        throw cause;
    }

    private String recoverLockContention(Throwable cause, CreateOrderRequest request) {
        log.warn("Order retries exhausted requestId={}", request.getRequestId());
        throw new ConcurrentOrderException(request.getRequestId(), cause);
    }

    public long getRetryCount() {
        return retryCount.get();
    }
}
