package com.esun.shop.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * "Today" for the admin dashboard: the Asia/Taipei calendar day containing {@code now}, plus the same instants as
 * wall-clock values in the zone the database timestamps are stored in. {@code shop_order.created_at} (DB default) and
 * the app-written payment timestamps hold UTC wall-clock in every deployment (docs/tasks/075 section 10), so a query
 * must compare against {@code storedFrom}/{@code storedTo}, never against Taipei wall-clock.
 */
public record DashboardWindow(OffsetDateTime from, OffsetDateTime to, LocalDateTime storedFrom, LocalDateTime storedTo) {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

    /** Half-open window {@code [from, to)}. */
    public static DashboardWindow today(Instant now, ZoneId storageZone) {
        ZonedDateTime start = LocalDate.ofInstant(now, BUSINESS_ZONE).atStartOfDay(BUSINESS_ZONE);
        ZonedDateTime end = start.plusDays(1);
        return new DashboardWindow(start.toOffsetDateTime(), end.toOffsetDateTime(),
                start.withZoneSameInstant(storageZone).toLocalDateTime(),
                end.withZoneSameInstant(storageZone).toLocalDateTime());
    }
}
