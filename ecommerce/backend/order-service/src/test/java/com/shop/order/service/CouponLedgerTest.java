package com.shop.order.service;

import com.shop.common.exception.BusinessException;
import com.shop.order.entity.Coupon;
import com.shop.order.entity.CouponDiscountType;
import com.shop.order.entity.CouponRedemption;
import com.shop.order.entity.Order;
import com.shop.order.repository.CouponRedemptionRepository;
import com.shop.order.repository.CouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponLedgerTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private CouponRedemptionRepository redemptionRepository;

    private CouponLedger ledger;
    private Coupon coupon;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ledger = new CouponLedger(couponRepository, redemptionRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        coupon = new Coupon();
        coupon.setId(3L);
        coupon.setCode("SALE500");
        coupon.setDiscountType(CouponDiscountType.FIXED_AMOUNT);
        coupon.setDiscountValue(new BigDecimal("500"));
        coupon.setActive(true);
    }

    @Test
    void lockApplicable_normalizesTheCodeAndLocksTheCoupon() {
        when(couponRepository.findByCodeForUpdate("SALE500")).thenReturn(Optional.of(coupon));

        assertThat(ledger.lockApplicable(" sale500 ", userId, new BigDecimal("6000"))).contains(coupon);
        assertThat(ledger.lockApplicable("  ", userId, new BigDecimal("6000"))).isEmpty();
        // No per-user limit: redemptions of the customer are not counted
        verifyNoInteractions(redemptionRepository);
    }

    @Test
    void lockApplicable_refusesUnknownCodesAndEnforcesThePerUserLimit() {
        when(couponRepository.findByCodeForUpdate("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> ledger.lockApplicable("nope", userId, BigDecimal.TEN))
                .isInstanceOf(BusinessException.class)
                .hasMessage("coupon.error.not-found");

        coupon.setPerUserLimit(1);
        when(couponRepository.findByCodeForUpdate("SALE500")).thenReturn(Optional.of(coupon));
        when(redemptionRepository.countByCouponIdAndUserIdAndReleasedAtIsNull(3L, userId)).thenReturn(1L);
        assertThatThrownBy(() -> ledger.lockApplicable("SALE500", userId, BigDecimal.TEN))
                .isInstanceOf(BusinessException.class)
                .hasMessage("coupon.error.user-limit");
    }

    @Test
    void redeem_countsTheUseAndRecordsTheOrder() {
        Order order = order();

        ledger.redeem(coupon, order);

        assertThat(coupon.getUsedCount()).isEqualTo(1);
        ArgumentCaptor<CouponRedemption> redemption = ArgumentCaptor.forClass(CouponRedemption.class);
        verify(redemptionRepository).save(redemption.capture());
        assertThat(redemption.getValue().getOrderId()).isEqualTo(order.getId());
        assertThat(redemption.getValue().getUserId()).isEqualTo(userId);
        assertThat(redemption.getValue().getDiscountAmount()).isEqualByComparingTo("500");
        assertThat(redemption.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void release_returnsTheCouponOfACancelledOrderOnce() {
        Order order = order();
        coupon.setUsedCount(1);
        CouponRedemption redemption = new CouponRedemption();
        redemption.setCoupon(coupon);
        redemption.setOrderId(order.getId());
        when(redemptionRepository.findByOrderIdAndReleasedAtIsNull(order.getId())).thenReturn(Optional.of(redemption));
        when(couponRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(coupon));

        ledger.release(order);

        assertThat(coupon.getUsedCount()).isZero();
        assertThat(redemption.getReleasedAt()).isEqualTo(NOW);
    }

    @Test
    void release_ignoresOrdersWithoutCoupon() {
        Order order = order();
        order.price(new BigDecimal("6000"), BigDecimal.ZERO);

        ledger.release(order);

        verify(redemptionRepository, never()).findByOrderIdAndReleasedAtIsNull(any());
        verifyNoInteractions(couponRepository);
    }

    private Order order() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNumber("ORD-1");
        order.setUserId(userId);
        order.setCurrency("RUB");
        order.price(new BigDecimal("6000"), BigDecimal.ZERO);
        order.applyCoupon("SALE500", new BigDecimal("500"));
        return order;
    }
}
