package com.esun.shop.service;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderDetailResponse;
import com.esun.shop.dto.OrderPageResponse;
import com.esun.shop.dto.OrderSummaryResponse;
import com.esun.shop.model.OrderDetail;
import com.esun.shop.model.PayStatus;
import com.esun.shop.model.ShopOrder;
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
    private final OrderRepository orderRepository;

    @Autowired
    public OrderService(OrderTransactionService transactionService, StockCacheService stockCacheService, OrderRepository orderRepository) {
        this.transactionService = transactionService;
        this.stockCacheService = stockCacheService;
        this.orderRepository = orderRepository;
    }

    public OrderService(OrderTransactionService transactionService) {
        this(transactionService, null, null);
    }

    /** Compatibility constructor used by isolated service unit tests. */
    public OrderService(ProductRepository productRepository, OrderRepository orderRepository) {
        this(new OrderTransactionService(productRepository, orderRepository), null, orderRepository);
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

    public OrderPageResponse getOrders(String memberId, int page, int size, Integer payStatus) {
        requireMember(memberId);
        if (page < 0 || size < 1 || size > 100 || !isPayStatus(payStatus)) {
            throw new BusinessException("分頁或付款狀態參數不合法", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        long total = orderRepository.countByMemberId(memberId, payStatus);
        List<OrderSummaryResponse> content = orderRepository.findByMemberId(memberId, payStatus, size, page * size).stream()
                .map(order -> new OrderSummaryResponse(order.getOrderId(), order.getPrice(), order.getPayStatus(), order.getCreatedAt()))
                .toList();
        return new OrderPageResponse(content, page, size, total, (int) Math.ceil((double) total / size));
    }

    public OrderDetailResponse getOrderDetail(String orderId, String memberId) {
        requireMember(memberId);
        ShopOrder order = orderRepository.findOrderById(orderId)
                .orElseThrow(() -> new BusinessException("訂單不存在", org.springframework.http.HttpStatus.NOT_FOUND));
        if (!memberId.equals(order.getMemberId())) {
            throw new BusinessException("無權存取此訂單", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        List<OrderDetailResponse.Item> items = orderRepository.findDetailsByOrderId(orderId).stream()
                .map(item -> new OrderDetailResponse.Item(item.getProductId(), item.getQuantity(), item.getUnitPrice(), item.getItemPrice()))
                .toList();
        return new OrderDetailResponse(order.getOrderId(), order.getPrice(), order.getPayStatus(), order.getCreatedAt(), items);
    }

    private static boolean isPayStatus(Integer payStatus) {
        return payStatus == null || (payStatus >= 0 && payStatus < PayStatus.values().length);
    }

    private static void requireMember(String memberId) {
        if (memberId == null || memberId.isBlank()) {
            throw new BusinessException("缺少登入憑證", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
    }
}
