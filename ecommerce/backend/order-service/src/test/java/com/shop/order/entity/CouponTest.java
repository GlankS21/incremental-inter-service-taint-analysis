package com.shop.order.entity;

import com.shop.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    @Test
    void percentageIsRoundedHalfUpAndCapped() {
        Coupon coupon = coupon(CouponDiscountType.PERCENTAGE, "10");
        assertThat(coupon.discountFor(new BigDecimal("1234.55"))).isEqualByComparingTo("123.46");
        assertThat(coupon.discountFor(new BigDecimal("1234.45"))).isEqualByComparingTo("123.45");
        assertThat(coupon.discountFor(new BigDecimal("0.04"))).isEqualByComparingTo("0.00");

        coupon.setMaxDiscountAmount(new BigDecimal("5000"));
        assertThat(coupon.discountFor(new BigDecimal("40000"))).isEqualByComparingTo("4000");
        assertThat(coupon.discountFor(new BigDecimal("80000"))).isEqualByComparingTo("5000");
        assertThat(coupon.discountFor(new BigDecimal("80000")).scale()).isEqualTo(2);
    }

    @Test
    void discountAlwaysLeavesSomethingToPay() {
        assertThat(coupon(CouponDiscountType.FIXED_AMOUNT, "500").discountFor(new BigDecimal("6000")))
                .isEqualByComparingTo("500");
        assertThat(coupon(CouponDiscountType.FIXED_AMOUNT, "500").discountFor(new BigDecimal("300")))
                .isEqualByComparingTo("299");
        assertThat(coupon(CouponDiscountType.PERCENTAGE, "100").discountFor(new BigDecimal("999.99")))
                .isEqualByComparingTo("998.99");
        assertThat(coupon(CouponDiscountType.FIXED_AMOUNT, "500").discountFor(new BigDecimal("0.50")))
                .isEqualByComparingTo("0");
    }

    @Test
    void checksTheRulesInOrder() {
        Coupon coupon = coupon(CouponDiscountType.FIXED_AMOUNT, "500");
        coupon.setMinOrderAmount(new BigDecimal("5000"));
        coupon.setUsageLimit(2);
        coupon.setPerUserLimit(1);
        coupon.setStartsAt(NOW.plusSeconds(60));
        coupon.setExpiresAt(NOW.minusSeconds(60));
        coupon.setUsedCount(2);
        coupon.setActive(false);
        BigDecimal small = new BigDecimal("100");

        assertRejected(coupon, small, 1, "coupon.error.not-found");
        coupon.setActive(true);
        assertRejected(coupon, small, 1, "coupon.error.not-started");
        coupon.setStartsAt(NOW);
        assertRejected(coupon, small, 1, "coupon.error.expired");
        coupon.setExpiresAt(NOW);
        assertRejected(coupon, small, 1, "coupon.error.expired");
        coupon.setExpiresAt(NOW.plusSeconds(1));
        assertRejected(coupon, small, 1, "coupon.error.exhausted");
        coupon.setUsedCount(1);
        assertRejected(coupon, small, 1, "coupon.error.user-limit");
        assertThatThrownBy(() -> coupon.checkApplicable(NOW, small, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessage("coupon.error.min-amount")
                .satisfies(ex -> assertThat(((BusinessException) ex).getMessageArgs())
                        .containsExactly(new BigDecimal("5000")));

        coupon.checkApplicable(NOW, new BigDecimal("5000"), 0);
    }

    @Test
    void releaseMakesTheCouponAvailableAgain() {
        Coupon coupon = coupon(CouponDiscountType.FIXED_AMOUNT, "500");
        coupon.setUsageLimit(1);
        coupon.recordRedemption();
        assertRejected(coupon, BigDecimal.TEN, 0, "coupon.error.exhausted");

        coupon.recordRelease();
        coupon.recordRelease();
        assertThat(coupon.getUsedCount()).isZero();
        coupon.checkApplicable(NOW, BigDecimal.TEN, 0);
    }

    @Test
    void codesAreNormalized() {
        assertThat(Coupon.normalizeCode("  welcome10 ")).isEqualTo("WELCOME10");
        assertThat(Coupon.normalizeCode("   ")).isNull();
        assertThat(Coupon.normalizeCode(null)).isNull();
    }

    private static void assertRejected(Coupon coupon, BigDecimal subtotal, long redemptionsOfUser, String key) {
        assertThatThrownBy(() -> coupon.checkApplicable(NOW, subtotal, redemptionsOfUser))
                .isInstanceOf(BusinessException.class)
                .hasMessage(key);
    }

    private static Coupon coupon(CouponDiscountType type, String value) {
        Coupon coupon = new Coupon();
        coupon.setCode("TEST");
        coupon.setDiscountType(type);
        coupon.setDiscountValue(new BigDecimal(value));
        coupon.setActive(true);
        return coupon;
    }
}
