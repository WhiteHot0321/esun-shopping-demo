package com.esun.shop.dto;

import java.util.List;

public record OrderPageResponse(List<OrderView> orders, long total, int page, int size) { }
