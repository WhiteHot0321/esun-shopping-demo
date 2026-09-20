package com.esun.shop.dto;

import com.esun.shop.model.ProductReview;
import java.math.BigDecimal;
import java.util.List;

public record ReviewPageResponse(
        List<ProductReview> reviews,
        long total,
        int page,
        int size,
        BigDecimal averageRating,
        long reviewCount) {
}
