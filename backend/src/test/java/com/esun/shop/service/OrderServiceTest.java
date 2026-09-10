package com.esun.shop.service;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.OrderDetail;
import com.esun.shop.model.PayStatus;
import com.esun.shop.model.Product;
import com.esun.shop.model.ShopOrder;
import com.esun.shop.repository.OrderRepository;
import com.esun.shop.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderService}. Repositories are mocked, so these cover
 * the service's own price-calculation, validation and lock-ordering logic —
 * not the real stored-procedure/DB behavior (that's covered by the
 * Testcontainers integration tests).
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderRepository orderRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(productRepository, orderRepository);
    }

    private Product product(String id, String price, int quantity) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName("product " + id);
        p.setPrice(new BigDecimal(price));
        p.setQuantity(quantity);
        return p;
    }

    private OrderItemRequest item(String productId, int quantity) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    private CreateOrderRequest request(List<OrderItemRequest> items) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setMemberId("M001");
        request.setPayStatus(PayStatus.PENDING);
        request.setItems(items);
        return request;
    }

    @Test
    void createOrder_singleItem_calculatesTotalPriceAndPersistsOrder() {
        when(productRepository.findByIds(any())).thenReturn(List.of(product("P001", "100.00", 10)));

        CreateOrderRequest req = request(List.of(item("P001", 3)));

        String orderId = orderService.createOrder(req);

        assertThat(orderId).isNotBlank();

        ArgumentCaptor<ShopOrder> orderCaptor = ArgumentCaptor.forClass(ShopOrder.class);
        verify(orderRepository).insertOrder(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getPrice()).isEqualByComparingTo("300.00");
        assertThat(orderCaptor.getValue().getMemberId()).isEqualTo("M001");
        assertThat(orderCaptor.getValue().getPayStatus()).isEqualTo(PayStatus.PENDING.ordinal());

        verify(productRepository).decreaseStock("P001", 3);
    }

    @Test
    void createOrder_multipleItems_sumsItemPricesAcrossAllLines() {
        when(productRepository.findByIds(any())).thenReturn(List.of(
                product("P001", "100.00", 10),
                product("P002", "50.50", 10)));

        CreateOrderRequest req = request(List.of(item("P001", 2), item("P002", 4)));

        orderService.createOrder(req);

        // 2*100.00 + 4*50.50 = 200.00 + 202.00 = 402.00
        ArgumentCaptor<ShopOrder> orderCaptor = ArgumentCaptor.forClass(ShopOrder.class);
        verify(orderRepository).insertOrder(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getPrice()).isEqualByComparingTo("402.00");
    }

    @Test
    void createOrder_productNotFound_throws404AndDoesNotPersistAnything() {
        when(productRepository.findByIds(any())).thenReturn(List.of(product("P001", "100.00", 10)));

        CreateOrderRequest req = request(List.of(item("P001", 1), item("MISSING", 1)));

        assertThatThrownBy(() -> orderService.createOrder(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(orderRepository, never()).insertOrder(any());
        verify(orderRepository, never()).insertOrderDetail(any());
        verify(productRepository, never()).decreaseStock(anyString(), anyInt());
    }

    @Test
    void createOrder_insufficientStock_throws409AndDoesNotPersistAnything() {
        when(productRepository.findByIds(any())).thenReturn(List.of(product("P001", "100.00", 2)));

        CreateOrderRequest req = request(List.of(item("P001", 5)));

        assertThatThrownBy(() -> orderService.createOrder(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(orderRepository, never()).insertOrder(any());
        verify(orderRepository, never()).insertOrderDetail(any());
        verify(productRepository, never()).decreaseStock(anyString(), anyInt());
    }

    @Test
    void createOrder_stockValidationFailsOnSecondItem_neitherItemIsPersisted() {
        // First item has enough stock, second doesn't - the whole request must be
        // rejected before anything is written, not just the offending line.
        when(productRepository.findByIds(any())).thenReturn(List.of(
                product("P001", "100.00", 10),
                product("P002", "50.00", 1)));

        CreateOrderRequest req = request(List.of(item("P001", 1), item("P002", 5)));

        assertThatThrownBy(() -> orderService.createOrder(req))
                .isInstanceOf(BusinessException.class);

        verify(orderRepository, never()).insertOrder(any());
        verify(productRepository, never()).decreaseStock(anyString(), anyInt());
    }

    @Test
    void createOrder_duplicateProductIdsInRequest_fetchesProductsOnlyOnceViaSingleInQuery() {
        when(productRepository.findByIds(any())).thenReturn(List.of(product("P001", "100.00", 10)));

        CreateOrderRequest req = request(List.of(item("P001", 1), item("P001", 2)));

        orderService.createOrder(req);

        // 3.2 fix: exactly one batched IN-query for product lookup, regardless of how
        // many order lines reference the same product id - never one findById() per line.
        ArgumentCaptor<Collection<String>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(productRepository, times(1)).findByIds(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly("P001");
        verify(productRepository, never()).findById(anyString());
    }

    @Test
    void createOrder_multipleDistinctProducts_looksThemUpInOneBatchedQuery() {
        when(productRepository.findByIds(any())).thenReturn(List.of(
                product("P001", "100.00", 10),
                product("P002", "50.00", 10),
                product("P003", "10.00", 10)));

        CreateOrderRequest req = request(List.of(item("P001", 1), item("P002", 1), item("P003", 1)));

        orderService.createOrder(req);

        verify(productRepository, times(1)).findByIds(any());
        verify(productRepository, never()).findById(anyString());
    }

    @Test
    void createOrder_multipleItems_insertsOrderDetailsAndDecreasesStockInProductIdOrder() {
        // 3.3 fix: lock order must be deterministic (ascending productId) regardless of
        // the order items were submitted in, to avoid deadlocks under concurrent orders.
        when(productRepository.findByIds(any())).thenReturn(List.of(
                product("P003", "10.00", 10),
                product("P001", "100.00", 10),
                product("P002", "50.00", 10)));

        CreateOrderRequest req = request(List.of(item("P003", 1), item("P001", 1), item("P002", 1)));

        orderService.createOrder(req);

        ArgumentCaptor<OrderDetail> detailCaptor = ArgumentCaptor.forClass(OrderDetail.class);
        verify(orderRepository, times(3)).insertOrderDetail(detailCaptor.capture());
        assertThat(detailCaptor.getAllValues())
                .extracting(OrderDetail::getProductId)
                .containsExactly("P001", "P002", "P003");

        var inOrder = org.mockito.Mockito.inOrder(productRepository);
        inOrder.verify(productRepository).decreaseStock(eq("P001"), anyInt());
        inOrder.verify(productRepository).decreaseStock(eq("P002"), anyInt());
        inOrder.verify(productRepository).decreaseStock(eq("P003"), anyInt());
    }

    @Test
    void createOrder_generatesUniqueOrderIdsAcrossCalls() {
        when(productRepository.findByIds(any())).thenReturn(List.of(product("P001", "100.00", 10)));

        CreateOrderRequest req1 = request(List.of(item("P001", 1)));
        CreateOrderRequest req2 = request(List.of(item("P001", 1)));

        String orderId1 = orderService.createOrder(req1);
        String orderId2 = orderService.createOrder(req2);

        assertThat(orderId1).isNotEqualTo(orderId2);
        assertThat(orderId1).startsWith("Ms").hasSize(25);
        assertThat(orderId2).startsWith("Ms").hasSize(25);
    }
}
