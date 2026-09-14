package com.esun.shop.integration;

import com.esun.shop.dto.CreateOrderRequest;
import com.esun.shop.dto.CreateProductRequest;
import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.model.PayStatus;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.ProductService;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the 3.2 fix: OrderService.createOrder() must fetch every referenced product with a
 * single batched `SELECT ... WHERE product_id IN (...)` (ProductRepository.findByIds), never one
 * SELECT per item (the original 2n-SELECT bug). Eyeballing SQL logs doesn't prove this - a
 * regression here means N SELECTs for N items and nobody would notice from behavior alone (same
 * end result, worse latency at scale). So this test proxies the real datasource with
 * datasource-proxy (test-only dependency, see pom.xml) and literally counts how many times the
 * `findByIds` SQL text executes per createOrder() call.
 *
 * Uses a distinct product ID prefix ("QC-") so it never collides with rows left behind by the
 * other integration test classes sharing this JVM's singleton MySQL container.
 */
class OrderServiceQueryCountIntegrationTest extends AbstractMySqlIntegrationTest {

    /**
     * The exact SQL text ProductRepository.findByIds builds. Matching on this (rather than just
     * "product") keeps this test from also counting ProductRepository.findById's
     * `WHERE product_id = ?` (singular) calls or the sp_add_product/sp_get_available_products
     * stored-procedure calls, which use entirely different SQL text.
     */
    private static final String FIND_BY_IDS_SQL_MARKER = "FROM product WHERE product_id IN";

    /**
     * Static so the QueryExecutionListener (constructed once, inside the proxy, when the
     * ApplicationContext is built) and the test method (running later) share the same counter.
     */
    static final AtomicInteger FIND_BY_IDS_CALL_COUNT = new AtomicInteger();

    @TestConfiguration
    static class QueryCountingDataSourceConfig {

        /**
         * Wraps the real (Hikari) DataSource bean in a datasource-proxy ProxyDataSource right
         * after Spring Boot auto-configures it, so every JDBC statement that flows through
         * JdbcTemplate/SimpleJdbcCall is observable here. This is test-only wiring - no
         * production class is touched or aware this proxy exists.
         *
         * Declared as a static @Bean factory method per Spring's own guidance for
         * BeanPostProcessor beans, so it can be instantiated early without forcing other beans
         * (in particular the real DataSource it needs to wrap) to initialize prematurely.
         */
        @Bean
        static BeanPostProcessor findByIdsQueryCountingPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource) {
                        return ProxyDataSourceBuilder.create(dataSource)
                                .name("find-by-ids-query-counter")
                                .listener(new FindByIdsCountingListener())
                                .build();
                    }
                    return bean;
                }
            };
        }
    }

    static class FindByIdsCountingListener implements QueryExecutionListener {
        @Override
        public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
            // no-op: counting happens after execution so failed statements aren't miscounted.
        }

        @Override
        public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
            for (QueryInfo queryInfo : queryInfoList) {
                String sql = queryInfo.getQuery();
                if (sql != null && sql.contains(FIND_BY_IDS_SQL_MARKER)) {
                    FIND_BY_IDS_CALL_COUNT.incrementAndGet();
                }
            }
        }
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @BeforeEach
    void resetCounter() {
        FIND_BY_IDS_CALL_COUNT.set(0);
    }

    private void seedProduct(String productId, String price, int quantity) {
        CreateProductRequest request = new CreateProductRequest();
        request.setProductId(productId);
        request.setProductName("query count product " + productId);
        request.setPrice(new BigDecimal(price));
        request.setQuantity(quantity);
        productService.createProduct(request);
    }

    private OrderItemRequest item(String productId, int quantity) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    @Test
    void createOrder_threeDistinctItems_fetchesProductsWithExactlyOneInQuery() {
        seedProduct("QC-P001", "10.00", 50);
        seedProduct("QC-P002", "20.00", 50);
        seedProduct("QC-P003", "30.00", 50);

        CreateOrderRequest request = new CreateOrderRequest();
        request.setMemberId("QC-MEMBER-1");
        request.setPayStatus(PayStatus.PENDING);
        request.setItems(List.of(item("QC-P001", 1), item("QC-P002", 2), item("QC-P003", 3)));

        String orderId = orderService.createOrder(request);

        assertThat(orderId).isNotBlank();
        // The 3.2 fix: one batched `SELECT ... IN (...)` for all 3 items, never 3 separate
        // per-item SELECTs (the original 2n-SELECT bug this guards against).
        assertThat(FIND_BY_IDS_CALL_COUNT.get()).isEqualTo(1);
    }

    @Test
    void createOrder_duplicateProductIdAcrossItems_stillFetchesWithExactlyOneInQuery() {
        seedProduct("QC-P010", "15.00", 50);

        CreateOrderRequest request = new CreateOrderRequest();
        request.setMemberId("QC-MEMBER-2");
        request.setPayStatus(PayStatus.PENDING);
        // OrderService.createOrder() de-duplicates productIds (.distinct()) before building the
        // IN-query - repeating the same product across items must not turn into extra SELECTs.
        request.setItems(List.of(item("QC-P010", 1), item("QC-P010", 1)));

        String orderId = orderService.createOrder(request);

        assertThat(orderId).isNotBlank();
        assertThat(FIND_BY_IDS_CALL_COUNT.get()).isEqualTo(1);
    }

    @Test
    void createOrder_singleItem_alsoFetchesWithExactlyOneInQuery() {
        seedProduct("QC-P020", "5.00", 50);

        CreateOrderRequest request = new CreateOrderRequest();
        request.setMemberId("QC-MEMBER-3");
        request.setPayStatus(PayStatus.PENDING);
        request.setItems(List.of(item("QC-P020", 1)));

        String orderId = orderService.createOrder(request);

        assertThat(orderId).isNotBlank();
        assertThat(FIND_BY_IDS_CALL_COUNT.get()).isEqualTo(1);
    }
}
