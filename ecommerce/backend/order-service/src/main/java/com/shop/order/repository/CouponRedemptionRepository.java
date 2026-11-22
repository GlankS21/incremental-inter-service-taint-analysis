package com.shop.order.repository;

import com.shop.order.entity.CouponRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, Long> {

    /**
     * Redemptions of a coupon by a customer that still count towards its per-user limit.
     */
    long countByCouponIdAndUserIdAndReleasedAtIsNull(Long couponId, UUID userId);

    Optional<CouponRedemption> findByOrderIdAndReleasedAtIsNull(UUID orderId);

    /**
     * A coupon that was ever redeemed (even if released since) is kept for the order history.
     */
    boolean existsByCouponId(Long couponId);
}
