package com.esun.shop.docs;

import com.esun.shop.config.OpenApiConfig;
import com.esun.shop.controller.AuditLogController;
import com.esun.shop.controller.AuthController;
import com.esun.shop.controller.CartController;
import com.esun.shop.controller.CouponController;
import com.esun.shop.controller.MemberProfileController;
import com.esun.shop.controller.OrderController;
import com.esun.shop.controller.PaymentController;
import com.esun.shop.controller.ProductController;
import com.esun.shop.controller.ProductReviewController;
import com.esun.shop.controller.RecommendationController;
import com.esun.shop.controller.ShippingAddressController;
import com.esun.shop.controller.SupportController;
import com.esun.shop.repository.MemberRepository;
import com.esun.shop.security.JwtAuthFilter;
import com.esun.shop.security.JwtService;
import com.esun.shop.service.AuditLogService;
import com.esun.shop.service.AuthService;
import com.esun.shop.service.CartService;
import com.esun.shop.service.CouponService;
import com.esun.shop.service.OrderService;
import com.esun.shop.service.OrderStatusService;
import com.esun.shop.service.PaymentCallbackService;
import com.esun.shop.service.PaymentService;
import com.esun.shop.service.ProductReviewService;
import com.esun.shop.service.ProductService;
import com.esun.shop.service.RecommendationService;
import com.esun.shop.service.ShippingAddressService;
import com.esun.shop.service.SupportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigParameters;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Iterator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3.2 #17: the generated OpenAPI spec and Swagger UI are reachable without a token, describe the real
 * controllers, and mark bearer auth exactly where {@link JwtAuthFilter#isPublicRoute} enforces it. Runs the
 * real filter + springdoc against every controller with mocked services (no database needed).
 */
@WebMvcTest(controllers = {AuditLogController.class, AuthController.class, CartController.class,
        CouponController.class, MemberProfileController.class, OrderController.class, PaymentController.class,
        ProductController.class, ProductReviewController.class, RecommendationController.class,
        ShippingAddressController.class, SupportController.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class, SwaggerConfig.class, SwaggerUiConfigProperties.class,
        SwaggerUiConfigParameters.class, SwaggerUiOAuthProperties.class})
@Import({JwtAuthFilter.class, JwtService.class, OpenApiConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=integration-test-secret-key-at-least-32-bytes-long",
        "jwt.expiration-ms=3600000"
})
class OpenApiDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean private AuditLogService auditLogService;
    @MockBean private AuthService authService;
    @MockBean private CartService cartService;
    @MockBean private CouponService couponService;
    @MockBean private MemberRepository memberRepository;
    @MockBean private OrderService orderService;
    @MockBean private OrderStatusService orderStatusService;
    @MockBean private PaymentService paymentService;
    @MockBean private PaymentCallbackService paymentCallbackService;
    @MockBean private ProductService productService;
    @MockBean private ProductReviewService productReviewService;
    @MockBean private RecommendationService recommendationService;
    @MockBean private ShippingAddressService shippingAddressService;
    @MockBean private SupportService supportService;

    private JsonNode spec() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void specIsPublicAndDescribesTheApi() throws Exception {
        JsonNode spec = spec();

        assertThat(spec.at("/openapi").asText()).startsWith("3.");
        assertThat(spec.at("/info/title").asText()).isEqualTo("esun-shopping API");
        assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(spec.at("/paths/~1api~1orders/post/summary").asText()).isNotBlank();
        assertThat(spec.at("/paths/~1api~1cart~1checkout/post/tags/0").asText()).contains("Cart");
    }

    @Test
    void everyOperationIsDocumentedWithASummaryAndATag() throws Exception {
        JsonNode paths = spec().get("paths");

        assertThat(paths.size()).isGreaterThanOrEqualTo(40);
        Iterator<Map.Entry<String, JsonNode>> pathIt = paths.fields();
        while (pathIt.hasNext()) {
            Map.Entry<String, JsonNode> path = pathIt.next();
            Iterator<Map.Entry<String, JsonNode>> opIt = path.getValue().fields();
            while (opIt.hasNext()) {
                Map.Entry<String, JsonNode> op = opIt.next();
                assertThat(op.getValue().path("summary").asText())
                        .as("summary of %s %s", op.getKey(), path.getKey()).isNotBlank();
                assertThat(op.getValue().path("tags").size())
                        .as("tags of %s %s", op.getKey(), path.getKey()).isPositive();
            }
        }
    }

    @Test
    void bearerRequirementMirrorsTheFilterPublicRoutePolicy() throws Exception {
        JsonNode paths = spec().get("paths");
        int publicOps = 0;
        int protectedOps = 0;

        Iterator<Map.Entry<String, JsonNode>> pathIt = paths.fields();
        while (pathIt.hasNext()) {
            Map.Entry<String, JsonNode> path = pathIt.next();
            Iterator<Map.Entry<String, JsonNode>> opIt = path.getValue().fields();
            while (opIt.hasNext()) {
                Map.Entry<String, JsonNode> op = opIt.next();
                boolean isPublic = JwtAuthFilter.isPublicRoute(op.getKey().toUpperCase(), path.getKey());
                boolean hasBearer = op.getValue().path("security").toString().contains("bearerAuth");
                assertThat(hasBearer).as("%s %s", op.getKey(), path.getKey()).isEqualTo(!isPublic);
                if (isPublic) {
                    publicOps++;
                } else {
                    protectedOps++;
                }
            }
        }
        assertThat(publicOps).isPositive();
        assertThat(protectedOps).isGreaterThan(publicOps);
        // spot checks against the documented contract
        assertThat(paths.at("/~1api~1auth~1login/post/security").isMissingNode()).isTrue();
        assertThat(paths.at("/~1api~1orders/post/security").toString()).contains("bearerAuth");
    }

    @Test
    void swaggerUiIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        // The UI's static resources are only wired in a full application context (checked against the running
        // app); here it is enough that the filter lets the request through instead of answering 401.
        assertThat(mockMvc.perform(get("/swagger-ui/index.html")).andReturn().getResponse().getStatus())
                .isNotEqualTo(401);
    }

    @Test
    void docsExemptionDoesNotWeakenOtherRoutes() throws Exception {
        mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v3/api-docs")).andExpect(status().isUnauthorized());
    }
}
