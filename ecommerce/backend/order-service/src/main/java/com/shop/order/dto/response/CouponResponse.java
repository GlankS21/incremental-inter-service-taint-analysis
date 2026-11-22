package com.shop.order.dto.response;

import com.shop.order.entity.CouponDiscountType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @param usedCount redemptions not released by a cancellation
 */
public record CouponResponse(
        Long id,
        String code,
        String description,
        CouponDiscountType discountType,
        BigDecimal discountValue,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscountAmount,
        Instant startsAt,
        Instant expiresAt,
        Integer usageLimit,
        int usedCount,
        Integer perUserLimit,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
