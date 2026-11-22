package com.shop.order.service;

import com.shop.common.exception.BusinessException;
import com.shop.order.entity.Coupon;
import com.shop.order.entity.CouponRedemption;
import com.shop.order.entity.Order;
import com.shop.order.repository.CouponRedemptionRepository;
import com.shop.order.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Redemptions of coupons by orders. A coupon is redeemed in the transaction that saves the order and
 * released in the transaction that cancels it, both under the row lock of the coupon so that its
 * usage limits hold under concurrent checkouts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponLedger {

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository redemptionRepository;
    private final Clock clock;

    /**
     * Read-only check used by the preview: nothing is locked or reserved.
     *
     * @param code as entered by the customer
     * @throws BusinessException when the coupon does not exist or cannot be used for this order
     */
    @Transactional(readOnly = true)
    public Coupon findApplicable(String code, UUID userId, BigDecimal subtotal) {
        Coupon coupon = Optional.ofNullable(Coupon.normalizeCode(code))
                .flatMap(couponRepository::findByCode)
                .orElseThrow(() -> new BusinessException("coupon.error.not-found"));
        return checked(coupon, userId, subtotal);
    }

    /**
     * Locks and checks the coupon for a checkout; the lock is held until the order is committed.
     *
     * @param code as entered by the customer; blank means no coupon
     * @return empty without coupon
     * @throws BusinessException when the coupon does not exist or cannot be used for this order
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Coupon> lockApplicable(String code, UUID userId, BigDecimal subtotal) {
        String normalized = Coupon.normalizeCode(code);
        if (normalized == null) {
            return Optional.empty();
        }
        Coupon coupon = couponRepository.findByCodeForUpdate(normalized)
                .orElseThrow(() -> new BusinessException("coupon.error.not-found"));
        return Optional.of(checked(coupon, userId, subtotal));
    }

    /**
     * Records the use of a coupon locked by {@link #lockApplicable} by the saved order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void redeem(Coupon coupon, Order order) {
        coupon.recordRedemption();
        CouponRedemption redemption = new CouponRedemption();
        redemption.setCoupon(coupon);
        redemption.setOrderId(order.getId());
        redemption.setUserId(order.getUserId());
        redemption.setDiscountAmount(order.getDiscountAmount());
        redemption.setCreatedAt(clock.instant());
        redemptionRepository.save(redemption);
        log.info("Coupon {} redeemed by order {} ({} {})", coupon.getCode(), order.getOrderNumber(),
                order.getDiscountAmount(), order.getCurrency());
    }

    /**
     * Makes the coupon of a cancelled order available again. Does nothing for an order without
     * coupon or whose redemption is already released.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Order order) {
        if (order.getCouponCode() == null) {
            return;
        }
        redemptionRepository.findByOrderIdAndReleasedAtIsNull(order.getId()).ifPresent(redemption -> {
            couponRepository.findByIdForUpdate(redemption.getCoupon().getId()).ifPresent(Coupon::recordRelease);
            redemption.setReleasedAt(clock.instant());
            log.info("Coupon {} released by cancelled order {}", order.getCouponCode(), order.getOrderNumber());
        });
    }

    private Coupon checked(Coupon coupon, UUID userId, BigDecimal subtotal) {
        long redemptionsOfUser = coupon.getPerUserLimit() == null ? 0
                : redemptionRepository.countByCouponIdAndUserIdAndReleasedAtIsNull(coupon.getId(), userId);
        coupon.checkApplicable(clock.instant(), subtotal, redemptionsOfUser);
        return coupon;
    }
}
