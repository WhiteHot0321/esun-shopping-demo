package com.esun.shop.security;

import com.esun.shop.controller.OrderController;
import com.esun.shop.controller.ProductController;
import com.esun.shop.model.Product;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end exercise of {@link JwtAuthFilter} sitting in front of the real controllers,
 * via MockMvc - not a mocked-out unit test of the filter in isolation. The real
 * {@link JwtService} is brought in with {@code @Import} (it's a plain {@code @Component},
 * so {@code @WebMvcTest} doesn't scan it by default) and a fixed test secret, so tokens
 * used here are validated by the exact same signature/expiry logic production uses; only
 * OrderService/ProductService are mocked, same as the rest of this codebase's MockMvc tests.
 *
 * Confirms the endpoint-protection decision: POST /api/orders and POST /api/products
 * require a valid Bearer token (401 without one, 200 with one, same response shape as
 * before), while GET /api/products/available stays public for browsing.
 */
@WebMvcTest(controllers = {OrderController.class, ProductController.class})
@Import({JwtAuthFilter.class, JwtService.class})
@TestPropertySource(properties = {
        "jwt.secret=integration-test-secret-key-at-least-32-bytes-long",
        "jwt.expiration-ms=3600000"
})
class JwtAuthFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private OrderService orderService;

    @MockBean
    private ProductService productService;

    private String validAuthHeader() {
        return "Bearer " + jwtService.generateToken("user@example.com");
    }

    @Test
    void createOrder_withoutToken_returns401AndNeverCallsService() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "requestId", "00000000-0000-4000-8000-000000000001",
                "memberId", "M001",
                "payStatus", "PENDING",
                "items", List.of(Map.of("productId", "P001", "quantity", 1))));

        mockMvc.perform(post("/api/orders").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verify(orderService, never()).createOrder(any());
    }

    @Test
    void createOrder_withInvalidToken_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "requestId", "00000000-0000-4000-8000-000000000002",
                "memberId", "M001",
                "payStatus", "PENDING",
                "items", List.of(Map.of("productId", "P001", "quantity", 1))));

        mockMvc.perform(post("/api/orders")
                        .header(AUTHORIZATION, "Bearer not-a-real-jwt")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());

        verify(orderService, never()).createOrder(any());
    }

    @Test
    void createOrder_withValidToken_succeedsWithSameResponseShapeAsBefore() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "requestId", "00000000-0000-4000-8000-000000000003",
                "memberId", "M001",
                "payStatus", "PENDING",
                "items", List.of(Map.of("productId", "P001", "quantity", 1))));

        when(orderService.createOrder(any())).thenReturn("ORD123");

        mockMvc.perform(post("/api/orders")
                        .header(AUTHORIZATION, validAuthHeader())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value("ORD123"));
    }

    @Test
    void createProduct_withoutToken_returns401AndNeverCallsService() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "productId", "P100",
                "productName", "new product",
                "price", "9.99",
                "quantity", 5));

        mockMvc.perform(post("/api/products").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());

        verify(productService, never()).createProduct(any());
    }

    @Test
    void createProduct_withValidToken_succeeds() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "productId", "P100",
                "productName", "new product",
                "price", "9.99",
                "quantity", 5));

        mockMvc.perform(post("/api/products")
                        .header(AUTHORIZATION, validAuthHeader())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(productService).createProduct(any());
    }

    @Test
    void getAvailableProducts_withoutToken_staysPublicAndReturns200() throws Exception {
        Product p = new Product();
        p.setProductId("P001");
        p.setProductName("existing product");
        p.setPrice(new BigDecimal("10.00"));
        p.setQuantity(3);
        when(productService.getAvailableProducts()).thenReturn(List.of(p));

        mockMvc.perform(get("/api/products/available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].productId").value("P001"));
    }
}
