package com.esun.shop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Path;

@Configuration
public class CorsConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer(@Value("${product.images.directory:uploads/products}") String imageDirectory) {
        String location = Path.of(imageDirectory).toAbsolutePath().normalize().toUri().toString();
        // Path.toUri() 只在目錄已存在時才補結尾 "/"；全新部署時目錄尚未建立，缺少斜線會讓資源 location 指向兄弟路徑。
        String imageLocation = location.endsWith("/") ? location : location + "/";
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("http://localhost:5173")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }

            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/uploads/products/**").addResourceLocations(imageLocation);
            }
        };
    }
}
