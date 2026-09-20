package com.esun.shop.service;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.PayStatus;
import com.esun.shop.model.Product;
import com.esun.shop.repository.CartRepository;
import com.esun.shop.repository.CartRepository.CartItem;
import com.esun.shop.repository.OrderRepository;
import com.esun.shop.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CartService {
    private final CartRepository repository;
    private final ProductRepository productRepository;
    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public CartService(CartRepository repository, ProductRepository productRepository,
            OrderService orderService, OrderRepository orderRepository) {
        this.repository = repository;
        this.productRepository = productRepository;
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public List<CartItem> list(String email) {
        long memberId = requireMemberLock(email);
        for (CartItem item : repository.findAll(email)) {
            if (item.stock() <= 0) repository.delete(item.id(), memberId);
            else if (item.quantity() > item.stock()) repository.update(item.id(), memberId, item.stock());
        }
        return repository.findAll(email);
    }

    @Transactional
    public CartItem add(String email, String productId, int quantity) {
        long memberId = requireMemberLock(email);
        Product product = requireProduct(productId);
        CartItem current = repository.findByProduct(productId, email).orElse(null);
        int next = Math.addExact(current == null ? 0 : current.quantity(), quantity);
        requireAvailable(product, next);
        if (current == null) repository.insert(memberId, productId, next);
        else repository.update(current.id(), memberId, next);
        return repository.findByProduct(productId, email).orElseThrow();
    }

    @Transactional
    public CartItem update(String email, long itemId, int quantity) {
        long memberId = requireMemberLock(email);
        CartItem current = requireOwned(itemId, email);
        requireAvailable(requireProduct(current.productId()), quantity);
        repository.update(itemId, memberId, quantity);
        return requireOwned(itemId, email);
    }

    @Transactional
    public void delete(String email, long itemId) {
        long memberId = requireMemberLock(email);
        requireOwned(itemId, email);
        repository.delete(itemId, memberId);
    }

    @Transactional
    public void clear(String email) {
        repository.clear(requireMemberLock(email));
    }

    @Transactional
    public String checkout(String email, String requestId, Long shippingAddressId) {
        long memberId = requireMemberLock(email);
        var replay = orderRepository.findRequestById(requestId);
        if (replay.isPresent()) {
            if (!email.equals(replay.get().getMemberId())) {
                throw new BusinessException("requestId 已被其他會員使用", HttpStatus.CONFLICT);
            }
            return replay.get().getOrderId();
        }

        List<CartItem> cart = repository.findAll(email);
        if (cart.isEmpty()) throw new BusinessException("購物車不可為空", HttpStatus.BAD_REQUEST);

        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(requestId);
        request.setMemberId(email);
        request.setPayStatus(PayStatus.PENDING);
        request.setShippingAddressId(shippingAddressId);
        request.setItems(cart.stream().map(item -> {
            OrderItemRequest line = new OrderItemRequest();
            line.setProductId(item.productId());
            line.setQuantity(item.quantity());
            return line;
        }).toList());

        String orderId = orderService.createOrder(request);
        repository.clear(memberId);
        return orderId;
    }

    private long requireMemberLock(String email) {
        Long memberId = repository.lockMember(email);
        if (memberId == null) throw new BusinessException("會員不存在", HttpStatus.NOT_FOUND);
        return memberId;
    }

    private CartItem requireOwned(long itemId, String email) {
        return repository.findOwned(itemId, email)
                .orElseThrow(() -> new BusinessException("購物車項目不存在", HttpStatus.NOT_FOUND));
    }

    private Product requireProduct(String productId) {
        Product product = productRepository.findById(productId);
        if (product == null) throw new BusinessException("商品不存在", HttpStatus.NOT_FOUND);
        return product;
    }

    private void requireAvailable(Product product, int quantity) {
        if (quantity > product.getQuantity()) {
            throw new BusinessException("商品庫存不足", HttpStatus.CONFLICT);
        }
    }
}
