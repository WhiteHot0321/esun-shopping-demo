package com.esun.shop.service;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.OrderDetail;
import com.esun.shop.model.Product;
import com.esun.shop.model.ShopOrder;
import com.esun.shop.repository.OrderRepository;
import com.esun.shop.repository.ProductRepository;
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
public class OrderService {
    private static final DateTimeFormatter ORDER_ID_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final char[] SUFFIX_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final int SUFFIX_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    public OrderService(ProductRepository productRepository, OrderRepository orderRepository) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public String createOrder(CreateOrderRequest request) {
        String orderId = generateOrderId();

        // 一次撈回所有相關商品，避免驗證迴圈 + 明細迴圈各查一次造成的 2n 次 SELECT。
        List<String> productIds = request.getItems().stream()
                .map(OrderItemRequest::getProductId)
                .distinct()
                .toList();
        Map<String, Product> productMap = productRepository.findByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getProductId, product -> product));

        BigDecimal totalPrice = BigDecimal.ZERO;
        for (OrderItemRequest item : request.getItems()) {
            Product product = productMap.get(item.getProductId());
            if (product == null) {
                throw new BusinessException("商品不存在: " + item.getProductId(), HttpStatus.NOT_FOUND);
            }
            if (item.getQuantity() > product.getQuantity()) {
                throw new BusinessException("商品庫存不足: " + item.getProductId(), HttpStatus.CONFLICT);
            }
            totalPrice = totalPrice.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        ShopOrder order = new ShopOrder();
        order.setOrderId(orderId);
        order.setMemberId(request.getMemberId().trim());
        order.setPrice(totalPrice);
        order.setPayStatus(request.getPayStatus().ordinal());
        orderRepository.insertOrder(order);

        // 依 productId 升冪處理，讓並發訂單永遠以相同順序鎖定 product 列，避免交錯上鎖造成 deadlock。
        List<OrderItemRequest> lockOrderedItems = request.getItems().stream()
                .sorted(Comparator.comparing(OrderItemRequest::getProductId))
                .toList();
        for (OrderItemRequest item : lockOrderedItems) {
            Product product = productMap.get(item.getProductId());

            OrderDetail detail = new OrderDetail();
            detail.setOrderId(orderId);
            detail.setProductId(item.getProductId());
            detail.setQuantity(item.getQuantity());
            detail.setUnitPrice(product.getPrice());
            detail.setItemPrice(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            orderRepository.insertOrderDetail(detail);

            productRepository.decreaseStock(item.getProductId(), item.getQuantity());
        }

        return orderId;
    }

    /**
     * 訂單編號 = Ms + 毫秒級時間戳 (17) + 6 碼隨機英數，共 25 字元，符合 order_id VARCHAR(30)。
     * 保留時間前綴讓編號仍可依時間排序 / 人工判讀，隨機尾碼負責同毫秒下單時的唯一性。
     */
    private String generateOrderId() {
        StringBuilder sb = new StringBuilder(25);
        sb.append("Ms").append(LocalDateTime.now().format(ORDER_ID_TIMESTAMP));
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(SUFFIX_ALPHABET[RANDOM.nextInt(SUFFIX_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
