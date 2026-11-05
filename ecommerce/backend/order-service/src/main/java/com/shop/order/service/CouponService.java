package com.shop.order.service;

import com.shop.common.dto.PageResponse;
import com.shop.order.dto.request.CouponRequest;
import com.shop.order.dto.response.CouponResponse;
import org.springframework.data.domain.Pageable;

/**
 * Administration of the coupons. Their use at checkout is handled by {@link CouponLedger}.
 */
public interface CouponService {

    /**
     * @param keyword part of the code or of the description, case-insensitive
     * @param active  {@code null} for active and inactive coupons
     */
    PageResponse<CouponResponse> searchCoupons(String keyword, Boolean active, Pageable pageable);

    CouponResponse getCoupon(Long couponId);

    /**
     * @throws com.shop.common.exception.ConflictException when the code is already used
     */
    CouponResponse createCoupon(CouponRequest request);

    /**
     * Replaces every editable field; the usage counter is kept.
     */
    CouponResponse updateCoupon(Long couponId, CouponRequest request);

    /**
     * Only a coupon that was never redeemed can be deleted; others are deactivated instead.
     *
     * @throws com.shop.common.exception.BusinessException when the coupon was redeemed
     */
    void deleteCoupon(Long couponId);
}
