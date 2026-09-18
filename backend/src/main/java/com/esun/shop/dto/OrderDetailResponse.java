package com.esun.shop.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(String orderId, BigDecimal price, Integer payStatus, LocalDateTime createdAt,
                                  List<Item> items) {
    public record Item(String productId, Integer quantity, BigDecimal unitPrice, BigDecimal itemPrice) { }
}
