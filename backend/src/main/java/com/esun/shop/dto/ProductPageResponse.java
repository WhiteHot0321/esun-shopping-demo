package com.esun.shop.dto;

import com.esun.shop.model.Product;
import java.util.List;

public record ProductPageResponse(List<Product> products, long total, int page, int size) { }
