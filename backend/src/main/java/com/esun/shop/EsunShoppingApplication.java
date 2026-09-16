package com.esun.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
@org.springframework.scheduling.annotation.EnableScheduling
public class EsunShoppingApplication {
    public static void main(String[] args) {
        SpringApplication.run(EsunShoppingApplication.class, args);
    }
}
