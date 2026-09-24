package com.esun.shop.dto;

import java.util.List;

public record CouponPageResponse(List<CouponView> items, long total, int page, int size) {
}
