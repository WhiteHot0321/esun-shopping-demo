package com.esun.shop.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Path;

@Configuration
public class CorsConfig {
    /**
     * CORS is a Servlet filter running before {@code JwtAuthFilter}, not an MVC mapping: the auth filter writes its
     * 401 before Spring MVC is reached, and a cross-origin response without Access-Control-Allow-Origin is hidden
     * by the browser, which the frontend saw as a network error instead of a 401 (so expired tokens were never
     * cleared). Ordering this first also answers preflight requests before any auth logic. Origins come from
     * {@code cors.allowed-origins} (CORS_ALLOWED_ORIGINS); the prod profile has no default and rejects "*".
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(
            @Value("${cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        for (String origin : allowedOrigins) {
            config.addAllowedOrigin(origin.trim());
        }
        config.addAllowedMethod("GET");
        config.addAllowedMethod("POST");
        config.addAllowedMethod("PUT");
        config.addAllowedMethod("DELETE");
        config.addAllowedMethod("OPTIONS");
        config.addAllowedHeader("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public WebMvcConfigurer corsConfigurer(@Value("${product.images.directory:uploads/products}") String imageDirectory) {
        String location = Path.of(imageDirectory).toAbsolutePath().normalize().toUri().toString();
        // Path.toUri() 只在目錄已存在時才補結尾 "/"；全新部署時目錄尚未建立，缺少斜線會讓資源 location 指向兄弟路徑。
        String imageLocation = location.endsWith("/") ? location : location + "/";
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/uploads/products/**").addResourceLocations(imageLocation);
            }
        };
    }
}
