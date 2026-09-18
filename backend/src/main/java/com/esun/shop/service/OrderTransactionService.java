package com.esun.shop.service;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.OrderDetail;
import com.esun.shop.model.OrderRequest;
import com.esun.shop.model.PayStatus;
import com.esun.shop.model.Product;
import com.esun.shop.model.ShopOrder;
import com.esun.shop.repository.OrderRepository;
import com.esun.shop.repository.ProductRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrderTransactionService {
    private static final DateTimeFormatter ORDER_ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final char[] SUFFIX_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    public OrderTransactionService(ProductRepository productRepository, OrderRepository orderRepository) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public String createOrder(CreateOrderRequest request) {
        return createOrderWithResult(request).orderId();
    }

    @Transactional
    public OrderCreationResult createOrderWithResult(CreateOrderRequest request) {
        return doCreateOrder(request, () -> {});
    }

    @Transactional
    public OrderCreationResult createOrderWithStock(CreateOrderRequest request, Runnable reserveStock) {
        return doCreateOrder(request, reserveStock);
    }

    private OrderCreationResult doCreateOrder(CreateOrderRequest request, Runnable reserveStock) {
        String orderId = generateOrderId();
        String memberId = request.getMemberId().trim();
        try {
            orderRepository.claimRequest(request.getRequestId(), orderId, memberId);
        } catch (DuplicateKeyException ex) {
            OrderRequest original = orderRepository.findRequestById(request.getRequestId()).orElseThrow(() -> ex);
            if (!memberId.equals(original.getMemberId())) {
                throw new BusinessException("requestId 已被其他會員使用", HttpStatus.CONFLICT);
            }
            return new OrderCreationResult(original.getOrderId(), false);
        }

        // Claim ownership before reserving Redis: concurrent replays wait here and
        // return the committed original without reserving any stock a second time.
        reserveStock.run();
        List<String> productIds = request.getItems().stream().map(OrderItemRequest::getProductId).distinct().toList();
        Map<String, Product> productMap = productRepository.findByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getProductId, product -> product));
        BigDecimal totalPrice = BigDecimal.ZERO;
        for (OrderItemRequest item : request.getItems()) {
            Product product = productMap.get(item.getProductId());
            if (product == null) throw new BusinessException("商品不存在: " + item.getProductId(), HttpStatus.NOT_FOUND);
            if (item.getQuantity() > product.getQuantity()) throw new BusinessException("商品庫存不足: " + item.getProductId(), HttpStatus.CONFLICT);
            totalPrice = totalPrice.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        ShopOrder order = new ShopOrder();
        order.setOrderId(orderId);
        order.setMemberId(memberId);
        order.setPrice(totalPrice);
        // Payment state is server-owned. A buyer must never create an already-paid
        // or shipped order by supplying a crafted request body.
        order.setPayStatus(PayStatus.PENDING.ordinal());
        orderRepository.insertOrder(order);
        for (OrderItemRequest item : request.getItems().stream().sorted(Comparator.comparing(OrderItemRequest::getProductId)).toList()) {
            Product product = productMap.get(item.getProductId());
            productRepository.decreaseStock(item.getProductId(), item.getQuantity());
            OrderDetail detail = new OrderDetail();
            detail.setOrderId(orderId);
            detail.setProductId(item.getProductId());
            detail.setQuantity(item.getQuantity());
            detail.setUnitPrice(product.getPrice());
            detail.setItemPrice(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            orderRepository.insertOrderDetail(detail);
        }
        return new OrderCreationResult(orderId, true);
    }

    private String generateOrderId() {
        StringBuilder sb = new StringBuilder(25);
        sb.append("Ms").append(LocalDateTime.now().format(ORDER_ID_TIMESTAMP));
        for (int i = 0; i < 6; i++) sb.append(SUFFIX_ALPHABET[RANDOM.nextInt(SUFFIX_ALPHABET.length)]);
        return sb.toString();
    }
}
