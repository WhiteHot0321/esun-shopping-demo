package com.esun.shop.service;

import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.dto.OrderPageResponse;
import com.esun.shop.dto.OrderView;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Member;
import com.esun.shop.model.OrderStatus;
import com.esun.shop.repository.OrderRepository;
import com.esun.shop.repository.OrderRepository.ItemRow;
import com.esun.shop.repository.OrderRepository.OrderHeader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Order lifecycle: buyer history/tracking/cancellation and seller/admin fulfilment transitions.
 *
 * Authorization is derived from the verified principal only. Every transition runs inside one transaction that
 * row-locks the order first, re-validates the state machine against the locked row, compare-and-sets the status
 * and appends the audit/timeline entry, so two concurrent actors (e.g. buyer cancel vs. seller ship) cannot both
 * succeed. Cancellation returns stock to MySQL in the same transaction (products locked in productId order, the
 * same order used by checkout) and to Redis only after commit.
 */
@Service
public class OrderStatusService {
    private final OrderRepository orderRepository;
    private final StockCacheService stockCacheService;

    public OrderStatusService(OrderRepository orderRepository, StockCacheService stockCacheService) {
        this.orderRepository = orderRepository;
        this.stockCacheService = stockCacheService;
    }

    // ---- buyer ----

    public OrderPageResponse listMine(String memberId, String status, int page, int size) {
        validatePaging(page, size);
        String normalized = parseStatusFilter(status);
        List<OrderHeader> headers = orderRepository.findHeadersByMember(memberId, normalized, size, page * size);
        return new OrderPageResponse(buildViews(headers, null, false), orderRepository.countByMember(memberId, normalized),
                page, size);
    }

    public OrderView getMine(String orderId, String memberId) {
        OrderHeader header = orderRepository.findHeader(orderId)
                .filter(h -> h.memberId().equals(memberId))
                .orElseThrow(OrderStatusService::notFound);
        return buildViews(List.of(header), null, false).get(0);
    }

    /** Buyer self-service: only the owner may cancel, and only before the order ships. */
    @Transactional
    public OrderView cancelMine(String orderId, String memberId) {
        OrderHeader header = orderRepository.lockHeader(orderId)
                .filter(h -> h.memberId().equals(memberId))
                .orElseThrow(OrderStatusService::notFound);
        applyTransition(header, OrderStatus.CANCELLED, memberId, Member.Role.BUYER);
        return buildViews(List.of(orderRepository.findHeader(orderId).orElseThrow(OrderStatusService::notFound)),
                null, false).get(0);
    }

    // ---- seller / admin ----

    public OrderPageResponse listForSeller(String email, Member.Role role, String status, int page, int size) {
        validatePaging(page, size);
        String normalized = parseStatusFilter(status);
        String scope = scopeOf(email, role);
        List<OrderHeader> headers = orderRepository.findHeadersForSeller(scope, normalized, size, page * size);
        return new OrderPageResponse(buildViews(headers, scope, true), orderRepository.countForSeller(scope, normalized),
                page, size);
    }

    public OrderView getForSeller(String orderId, String email, Member.Role role) {
        String scope = scopeOf(email, role);
        OrderHeader header = orderRepository.findHeader(orderId).orElseThrow(OrderStatusService::notFound);
        List<OrderView> views = buildViews(List.of(header), scope, true);
        if (scope != null && views.get(0).items().isEmpty()) throw notFound();
        return views.get(0);
    }

    /**
     * Fulfilment transition by a seller (only when every line belongs to that seller, since status is per order)
     * or an admin. Orders containing none of the seller's products are reported as 404.
     */
    @Transactional
    public OrderView transition(String orderId, OrderStatus target, String email, Member.Role role) {
        String scope = scopeOf(email, role);
        OrderHeader header = orderRepository.lockHeader(orderId).orElseThrow(OrderStatusService::notFound);
        if (scope != null) {
            List<ItemRow> items = orderRepository.findItems(List.of(orderId)).getOrDefault(orderId, List.of());
            if (items.stream().noneMatch(i -> scope.equals(i.creatorId()))) throw notFound();
            if (items.stream().anyMatch(i -> !scope.equals(i.creatorId()))) {
                throw new BusinessException("此訂單包含其他賣家的商品，無法由單一賣家變更狀態", HttpStatus.FORBIDDEN);
            }
        }
        applyTransition(header, target, email, role);
        return getForSeller(orderId, email, role);
    }

    // ---- internals ----

    private void applyTransition(OrderHeader locked, OrderStatus target, String actor, Member.Role actorRole) {
        OrderStatus current = OrderStatus.valueOf(locked.status());
        if (!current.canTransitionTo(target)) {
            throw new BusinessException("訂單目前狀態為 " + current + "，無法變更為 " + target, HttpStatus.CONFLICT);
        }
        if (orderRepository.updateStatus(locked.orderId(), current.name(), target.name()) != 1) {
            throw new BusinessException("訂單狀態已被其他人變更，請重新整理", HttpStatus.CONFLICT);
        }
        orderRepository.insertStatusHistory(locked.orderId(), current.name(), target.name(), actor, actorRole.name());
        if (target == OrderStatus.CANCELLED) restoreStock(locked.orderId());
    }

    private void restoreStock(String orderId) {
        // Sort in Java exactly like checkout (case-sensitive String order). MySQL's ORDER BY uses the column's
        // case-insensitive collation, which would lock mixed-case product ids in a different order and could
        // deadlock a concurrent checkout of the same products.
        List<ItemRow> items = orderRepository.findItems(List.of(orderId)).getOrDefault(orderId, List.of()).stream()
                .sorted(java.util.Comparator.comparing(ItemRow::productId)).toList();
        for (ItemRow item : items) orderRepository.restoreStock(item.productId(), item.quantity());
        List<OrderItemRequest> restored = items.stream().map(i -> {
            OrderItemRequest request = new OrderItemRequest();
            request.setProductId(i.productId());
            request.setQuantity(i.quantity());
            return request;
        }).toList();
        Runnable compensate = () -> stockCacheService.compensate(restored);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Never touch Redis for a cancellation that might still roll back.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    compensate.run();
                }
            });
        } else {
            compensate.run();
        }
    }

    /**
     * @param scope seller email whose lines are exposed, or null for unrestricted access
     * @param sellerView whether to compute seller-side actions and hide buyer identity
     */
    private List<OrderView> buildViews(List<OrderHeader> headers, String scope, boolean sellerView) {
        List<String> ids = headers.stream().map(OrderHeader::orderId).toList();
        Map<String, List<ItemRow>> items = orderRepository.findItems(ids);
        Map<String, List<OrderView.TimelineEntry>> history = orderRepository.findHistory(ids);
        return headers.stream().map(header -> {
            List<ItemRow> all = items.getOrDefault(header.orderId(), List.of());
            List<ItemRow> visible = scope == null ? all : all.stream().filter(i -> scope.equals(i.creatorId())).toList();
            boolean wholeOrder = visible.size() == all.size();
            OrderStatus status = OrderStatus.valueOf(header.status());
            List<String> actions;
            if (!sellerView) {
                actions = status.canTransitionTo(OrderStatus.CANCELLED) ? List.of(OrderStatus.CANCELLED.name()) : List.of();
            } else {
                actions = wholeOrder ? status.nextStatuses().stream().map(Enum::name).toList() : List.of();
            }
            BigDecimal price = wholeOrder ? header.price()
                    : visible.stream().map(ItemRow::itemPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new OrderView(header.orderId(), scope == null ? header.memberId() : null, header.status(), price,
                    header.createdAt(), header.receiverName(), header.receiverPhone(), header.shippingAddress(),
                    visible.stream().map(i -> new OrderView.Item(i.productId(), i.productName(), i.quantity(),
                            i.unitPrice(), i.itemPrice())).toList(),
                    history.getOrDefault(header.orderId(), List.of()), actions);
        }).toList();
    }

    /** ADMIN sees everything (null scope); SELLER is scoped to products they created. */
    private static String scopeOf(String email, Member.Role role) {
        if (role == Member.Role.ADMIN) return null;
        if (role == Member.Role.SELLER && email != null && !email.isBlank()) return email;
        throw new BusinessException("權限不足", HttpStatus.FORBIDDEN);
    }

    private static void validatePaging(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException("分頁參數不合法", HttpStatus.BAD_REQUEST);
        }
    }

    private static String parseStatusFilter(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("all")) return null;
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("訂單狀態篩選不合法", HttpStatus.BAD_REQUEST);
        }
    }

    private static BusinessException notFound() {
        return new BusinessException("找不到訂單", HttpStatus.NOT_FOUND);
    }
}
