package com.shop.order.entity;

/**
 * How the discount of a coupon is computed from the subtotal of the order.
 */
public enum CouponDiscountType {

    /** {@code discountValue} percent of the subtotal, optionally capped by {@code maxDiscountAmount}. */
    PERCENTAGE,

    /** {@code discountValue} in the order currency. */
    FIXED_AMOUNT
}
