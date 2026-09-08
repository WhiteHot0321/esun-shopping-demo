package com.esun.shop.service;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.OrderDetail;
import com.esun.shop.model.Product;
import com.esun.shop.model.ShopOrder;
import com.esun.shop.repository.OrderRepository;
import com.esun.shop.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class OrderService {
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    // 只精確到毫秒仍可能同時撞號，因此加上程序內遞增序號當作第二道防線；
    // 序號在 0-999 間循環，搭配毫秒時間戳，同一毫秒最多可產生 1000 組不重複編號。
    private final AtomicInteger orderSequence = new AtomicInteger(0);

    public OrderService(ProductRepository productRepository, OrderRepository orderRepository) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public String createOrder(CreateOrderRequest request) {
        String orderId = generateOrderId();
        BigDecimal totalPrice = BigDecimal.ZERO;

        for (OrderItemRequest item : request.getItems()) {
            Product product = productRepository.findById(item.getProductId());
            if (product == null) {
                throw new BusinessException("商品不存在: " + item.getProductId());
            }
            if (item.getQuantity() > product.getQuantity()) {
                throw new BusinessException("商品庫存不足: " + item.getProductId());
            }
            totalPrice = totalPrice.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        ShopOrder order = new ShopOrder();
        order.setOrderId(orderId);
        order.setMemberId(request.getMemberId().trim());
        order.setPrice(totalPrice);
        order.setPayStatus(Integer.valueOf(request.getPayStatus()));
        orderRepository.insertOrder(order);

        for (OrderItemRequest item : request.getItems()) {
            Product product = productRepository.findById(item.getProductId());

            OrderDetail detail = new OrderDetail();
            detail.setOrderId(orderId);
            detail.setProductId(item.getProductId());
            detail.setQuantity(item.getQuantity());
            detail.setStandPrice(product.getPrice());
            detail.setItemPrice(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            orderRepository.insertOrderDetail(detail);

            productRepository.decreaseStock(item.getProductId(), item.getQuantity());
        }

        return orderId;
    }

    private String generateOrderId() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        int seq = orderSequence.getAndUpdate(v -> (v + 1) % 1000);
        return String.format("Ms%s%03d", timestamp, seq);
    }
}
