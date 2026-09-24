package com.esun.shop.dto;

import com.esun.shop.model.Product;

/**
 * One recommended product. {@code reason} says which ranking tier produced it so the UI can label it honestly:
 * {@code CO_PURCHASE} (bought together with the anchor / the member's basket), {@code POPULAR} (bought in many orders)
 * or {@code NEW_ARRIVAL} (filler when there is not enough purchase history). {@code score} is the number of distinct
 * orders behind the signal; 0 for {@code NEW_ARRIVAL}.
 */
public record RecommendationItem(Product product, String reason, long score) { }
