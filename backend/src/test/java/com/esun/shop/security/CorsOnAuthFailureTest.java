package com.esun.shop.security;

import com.esun.shop.config.CorsConfig;
import com.esun.shop.controller.OrderController;
import com.esun.shop.controller.ProductController;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.OrderStatusService;
import com.esun.shop.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.ORIGIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A browser hides any cross-origin response that lacks {@code Access-Control-Allow-Origin}, so a 401 written by
 * {@link JwtAuthFilter} (which runs before Spring MVC) used to reach the frontend as an opaque network error
 * ("無法連線到伺服器") instead of a 401 - the expired-token cleanup in {@code api.js} never ran. These tests pin
 * that the CORS headers are applied ahead of the auth filter, so auth failures stay readable to the frontend.
 */
@WebMvcTest(controllers = {OrderController.class, ProductController.class})
@Import({JwtAuthFilter.class, JwtService.class, CorsConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=integration-test-secret-key-at-least-32-bytes-long",
        "jwt.expiration-ms=-3600000" // every token this test generates is already expired
})
class CorsOnAuthFailureTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private OrderService orderService;

    @MockBean
    private OrderStatusService orderStatusService;

    @MockBean
    private ProductService productService;

    @Test
    void expiredToken_401StillCarriesCorsHeader() throws Exception {
        String expired = "Bearer " + jwtService.generateToken("user@example.com");

        mockMvc.perform(get("/api/orders")
                        .header(ORIGIN, FRONTEND_ORIGIN)
                        .header(AUTHORIZATION, expired))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN));
    }

    @Test
    void missingToken_401StillCarriesCorsHeader() throws Exception {
        mockMvc.perform(get("/api/orders").header(ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN));
    }

    @Test
    void preflight_isAnsweredWithCorsHeader() throws Exception {
        mockMvc.perform(options("/api/orders")
                        .header(ORIGIN, FRONTEND_ORIGIN)
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN));
    }

    @Test
    void publicRoute_getsExactlyOneCorsHeader() throws Exception {
        var response = mockMvc.perform(get("/api/products/available").header(ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getHeaders(ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly(FRONTEND_ORIGIN);
    }

    @Test
    void unknownOrigin_isNotGrantedCorsHeader() throws Exception {
        var response = mockMvc.perform(get("/api/orders").header(ORIGIN, "http://evil.example"))
                .andReturn().getResponse();

        assertThat(response.getHeader(ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }
}
