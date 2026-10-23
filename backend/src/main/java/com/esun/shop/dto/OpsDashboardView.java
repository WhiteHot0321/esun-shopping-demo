package com.esun.shop.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Admin operations dashboard. Aggregates only: no member, address or seller identifiers. {@code asOf} is when the
 * database figures were read (they may be up to the cache TTL old); {@code redis} is read live on every request.
 */
public record OpsDashboardView(OffsetDateTime asOf, String timezone, Today today, long pendingPaymentCount,
                               long refundRequiredCount, LowStock lowStock, Redis redis) {
    /** Orders created in the Asia/Taipei day {@code [from, to)}; cancelled orders are excluded from both counts. */
    public record Today(OffsetDateTime from, OffsetDateTime to, long orderCount, BigDecimal revenue,
                        long paymentFailureCount) { }

    public record LowStock(int threshold, long count, List<Item> items) { }

    public record Item(String productId, String productName, int quantity) { }

    /** {@code scope} is always THIS_INSTANCE: the mode is process-local state, not a cluster-wide fact. */
    public record Redis(String mode, String scope) { }
}
