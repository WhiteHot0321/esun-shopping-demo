package com.esun.shop.config;

import com.esun.shop.security.JwtAuthFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI metadata for the generated spec. Every operation is documented as requiring the {@code bearerAuth}
 * JWT scheme except the routes {@link JwtAuthFilter#isPublicRoute} lets through, which are marked as open, so the
 * "Authorize" button in Swagger UI reflects what the filter really enforces. Role checks (buyer / seller / admin)
 * happen inside the controllers and are described in each operation's summary/description instead.
 */
@Configuration
public class OpenApiConfig {
    static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI shoppingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("esun-shopping API")
                        .version("1.0.0")
                        .description("購物車後端 API。回應統一為 ApiResponse {success, message, code, data}；"
                                + "錯誤以對應 HTTP 狀態碼（400/401/403/404/409/500）回傳。"
                                + "先呼叫 POST /api/auth/login 取得 token，再按右上角 Authorize 貼上（不需加 Bearer 前綴）。"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }

    @Bean
    public OpenApiCustomizer bearerSecurityCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                if (!JwtAuthFilter.isPublicRoute(method.name(), path)) {
                    operation.setSecurity(List.of(new SecurityRequirement().addList(BEARER)));
                }
            }));
        };
    }
}
