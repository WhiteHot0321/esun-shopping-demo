package com.esun.shop.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardWindowTest {
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void lastSecondOfTheTaipeiDayStillBelongsToThatDay() {
        // 2026-10-22 23:59:59 Taipei == 15:59:59 UTC
        DashboardWindow w = DashboardWindow.today(Instant.parse("2026-10-22T15:59:59Z"), UTC);

        assertThat(w.from().toString()).isEqualTo("2026-10-22T00:00+08:00");
        assertThat(w.to().toString()).isEqualTo("2026-10-23T00:00+08:00");
        assertThat(w.storedFrom()).isEqualTo(LocalDateTime.of(2026, 10, 21, 16, 0));
        assertThat(w.storedTo()).isEqualTo(LocalDateTime.of(2026, 10, 22, 16, 0));
    }

    @Test
    void taipeiMidnightStartsTheNextDay() {
        // 16:00:00 UTC is already 2026-10-23 00:00:00 in Taipei: the UTC date has not changed, the Taipei one has.
        DashboardWindow w = DashboardWindow.today(Instant.parse("2026-10-22T16:00:00Z"), UTC);

        assertThat(w.from().toString()).isEqualTo("2026-10-23T00:00+08:00");
        assertThat(w.storedFrom()).isEqualTo(LocalDateTime.of(2026, 10, 22, 16, 0));
        assertThat(w.storedTo()).isEqualTo(LocalDateTime.of(2026, 10, 23, 16, 0));
    }

    @Test
    void earlyMorningTaipeiIsStillThePreviousUtcDate() {
        // 03:00 Taipei on the 23rd == 19:00 UTC on the 22nd; a naive UTC-date window would pick the wrong day.
        DashboardWindow w = DashboardWindow.today(Instant.parse("2026-10-22T19:00:00Z"), UTC);

        assertThat(w.from().toLocalDate().toString()).isEqualTo("2026-10-23");
        assertThat(w.storedFrom()).isEqualTo(LocalDateTime.of(2026, 10, 22, 16, 0));
    }

    @Test
    void windowIsAlwaysExactlyOneDay() {
        DashboardWindow w = DashboardWindow.today(Instant.parse("2027-02-28T10:00:00Z"), UTC);

        assertThat(java.time.Duration.between(w.from(), w.to())).isEqualTo(java.time.Duration.ofDays(1));
        assertThat(java.time.Duration.between(w.storedFrom(), w.storedTo())).isEqualTo(java.time.Duration.ofDays(1));
    }

    @Test
    void storageZoneOfTaipeiLeavesBoundsAsTaipeiWallClock() {
        DashboardWindow w = DashboardWindow.today(Instant.parse("2026-10-22T05:00:00Z"), ZoneId.of("Asia/Taipei"));

        assertThat(w.storedFrom()).isEqualTo(LocalDateTime.of(2026, 10, 22, 0, 0));
        assertThat(w.storedTo()).isEqualTo(LocalDateTime.of(2026, 10, 23, 0, 0));
        assertThat(w.from().getOffset()).isEqualTo(ZoneOffset.ofHours(8));
    }
}
