package com.esun.shop.service;

import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.DiscountType;
import com.esun.shop.repository.CouponRepository.Coupon;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure pricing/eligibility rules; the locking and counters are covered against real MySQL in CouponIntegrationTest. */
class CouponServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 12, 0);

    private static Coupon coupon(DiscountType type, String value, String max, String min, Integer quota, int used) {
        return new Coupon(1, "C", type, new BigDecimal(value), max == null ? null : new BigDecimal(max),
                new BigDecimal(min), quota, used, 1, NOW.minusDays(1), NOW.plusDays(1), true, "admin", NOW.minusDays(2));
    }

    private static BigDecimal price(Coupon c, String subtotal) {
        return CouponService.validateAndPrice(c, new BigDecimal(subtotal), NOW);
    }

    @Test
    void percentDiscountRoundsToWholeDollarsHalfUp() {
        Coupon tenPercent = coupon(DiscountType.PERCENT, "10", null, "0", null, 0);
        assertThat(price(tenPercent, "255.00")).isEqualByComparingTo("26");   // 25.5 -> 26
        assertThat(price(tenPercent, "254.00")).isEqualByComparingTo("25");   // 25.4 -> 25
        assertThat(price(tenPercent, "300.00").scale()).isEqualTo(2);
    }

    @Test
    void percentDiscountIsCappedByMaxDiscount() {
        Coupon capped = coupon(DiscountType.PERCENT, "50", "100", "0", null, 0);
        assertThat(price(capped, "1000.00")).isEqualByComparingTo("100");
        assertThat(price(capped, "120.00")).isEqualByComparingTo("60");
    }

    @Test
    void fixedDiscountAppliesAsIs() {
        assertThat(price(coupon(DiscountType.FIXED, "60", null, "0", null, 0), "500.00")).isEqualByComparingTo("60");
    }

    @Test
    void payableAmountNeverDropsBelowOne() {
        Coupon huge = coupon(DiscountType.FIXED, "500", null, "0", null, 0);
        assertThat(price(huge, "40.00")).isEqualByComparingTo("39");
        assertThat(price(huge, "40.50")).isEqualByComparingTo("39.50");
        assertThatThrownBy(() -> price(huge, "1.00")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("不足");
        assertThatThrownBy(() -> price(huge, "0.50")).isInstanceOf(BusinessException.class);
    }

    @Test
    void windowBoundariesAreStartInclusiveExpiryExclusive() {
        Coupon c = new Coupon(1, "C", DiscountType.FIXED, BigDecimal.TEN, null, BigDecimal.ZERO, null, 0, 1,
                NOW, NOW.plusHours(1), true, "admin", NOW);
        assertThat(CouponService.validateAndPrice(c, new BigDecimal("100"), NOW)).isEqualByComparingTo("10");
        assertThatThrownBy(() -> CouponService.validateAndPrice(c, new BigDecimal("100"), NOW.minusNanos(1)))
                .hasMessageContaining("尚未開始");
        assertThatThrownBy(() -> CouponService.validateAndPrice(c, new BigDecimal("100"), NOW.plusHours(1)))
                .hasMessageContaining("已過期");
    }

    @Test
    void minimumOrderQuotaAndDisabledAreRefusedWithDistinctStatuses() {
        assertThatThrownBy(() -> price(coupon(DiscountType.FIXED, "10", null, "500", null, 0), "499.99"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(price(coupon(DiscountType.FIXED, "10", null, "500", null, 0), "500.00")).isEqualByComparingTo("10");

        Coupon full = coupon(DiscountType.FIXED, "10", null, "0", 5, 5);
        assertThatThrownBy(() -> price(full, "100.00"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(price(coupon(DiscountType.FIXED, "10", null, "0", 5, 4), "100.00")).isEqualByComparingTo("10");

        Coupon off = new Coupon(1, "C", DiscountType.FIXED, BigDecimal.TEN, null, BigDecimal.ZERO, null, 0, 1,
                NOW.minusDays(1), NOW.plusDays(1), false, "admin", NOW);
        assertThatThrownBy(() -> price(off, "100.00"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
