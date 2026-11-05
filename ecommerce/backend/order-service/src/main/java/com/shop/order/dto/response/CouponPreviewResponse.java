package com.shop.order.dto.response;

import com.shop.order.entity.CouponDiscountType;

import java.math.BigDecimal;

/**
 * Amounts the order would have with the coupon, computed exactly as at checkout:
 * {@code totalAmount = subtotal − discountAmount + shippingFee}, the shipping fee depending on the
 * subtotal before discount.
 */
public record CouponPreviewResponse(
        String code,
        String description,
        CouponDiscountType discountType,
        BigDecimal discountValue,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal shippingFee,
        BigDecimal totalAmount,
        String currency
) {
}
