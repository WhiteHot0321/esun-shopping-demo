package com.esun.shop.config;

import com.esun.shop.controller.OrderController;
import com.esun.shop.security.JwtAuthFilter;
import com.esun.shop.security.JwtService;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.OrderStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ORIGIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Phase 3.3 #18 B1: allowed CORS origins come from {@code cors.allowed-origins}, not from a hard-coded dev URL. */
@WebMvcTest(controllers = OrderController.class)
@Import({JwtAuthFilter.class, JwtService.class, CorsConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=integration-test-secret-key-at-least-32-bytes-long",
        "cors.allowed-origins=https://shop.example.com, https://www.example.com"
})
class CorsConfiguredOriginsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private OrderStatusService orderStatusService;

    private String allowOriginFor(String origin) throws Exception {
        return mockMvc.perform(get("/api/orders").header(ORIGIN, origin)).andReturn().getResponse()
                .getHeader(ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void everyConfiguredOrigin_isAllowed_evenOnA401() throws Exception {
        assertThat(allowOriginFor("https://shop.example.com")).isEqualTo("https://shop.example.com");
        assertThat(allowOriginFor("https://www.example.com")).isEqualTo("https://www.example.com");
    }

    @Test
    void theDevServerOrigin_isNoLongerAllowedOnceConfigured() throws Exception {
        assertThat(allowOriginFor("http://localhost:5173")).isNull();
    }
}
