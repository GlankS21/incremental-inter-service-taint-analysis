package com.shop.order.entity;

import com.shop.common.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;

/**
 * Discount code entered at checkout. The validity rules ({@link #checkApplicable}) and the discount
 * rule ({@link #discountFor}) live here so that the checkout and its preview always agree.
 * <p>
 * {@code usedCount} counts the redemptions that were not released by a cancellation; it is only
 * changed under the row lock of the coupon.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "coupons")
public class Coupon extends AuditableEntity {

    public static final String CODE_PATTERN = "^[A-Za-z0-9_-]{3,30}$";
    public static final int MAX_CODE_LENGTH = 30;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * Part of the subtotal a coupon never covers: payment providers refuse a zero amount, so an order
     * always has something to pay even with free shipping.
     */
    static final BigDecimal MIN_PAYABLE = new BigDecimal("1.00");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = MAX_CODE_LENGTH)
    private String code;

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private CouponDiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "min_order_amount", precision = 14, scale = 2)
    private BigDecimal minOrderAmount;

    @Column(name = "max_discount_amount", precision = 14, scale = 2)
    private BigDecimal maxDiscountAmount;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "per_user_limit")
    private Integer perUserLimit;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    /**
     * Codes are compared case-insensitively and stored in upper case.
     *
     * @return the stored form of {@code code}, {@code null} when it is blank
     */
    public static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Checks, in this order: active, started, not expired, global usage limit, usage limit of the
     * customer, minimum order amount.
     *
     * @param redemptionsOfUser redemptions of this coupon by the customer that were not released
     * @throws BusinessException with the message key of the first rule that fails
     */
    public void checkApplicable(Instant now, BigDecimal subtotal, long redemptionsOfUser) {
        if (!active) {
            throw new BusinessException("coupon.error.not-found");
        }
        if (startsAt != null && now.isBefore(startsAt)) {
            throw new BusinessException("coupon.error.not-started");
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            throw new BusinessException("coupon.error.expired");
        }
        if (usageLimit != null && usedCount >= usageLimit) {
            throw new BusinessException("coupon.error.exhausted");
        }
        if (perUserLimit != null && redemptionsOfUser >= perUserLimit) {
            throw new BusinessException("coupon.error.user-limit");
        }
        if (minOrderAmount != null && subtotal.compareTo(minOrderAmount) < 0) {
            throw new BusinessException("coupon.error.min-amount", minOrderAmount);
        }
    }

    /**
     * PERCENTAGE: {@code subtotal × value / 100} rounded half-up to 2 decimals, capped by
     * {@code maxDiscountAmount}; FIXED_AMOUNT: the value. Never more than the subtotal minus
     * {@link #MIN_PAYABLE}.
     */
    public BigDecimal discountFor(BigDecimal subtotal) {
        BigDecimal discount = switch (discountType) {
            case PERCENTAGE -> {
                BigDecimal percentage = subtotal.multiply(discountValue).divide(HUNDRED, 2, RoundingMode.HALF_UP);
                yield maxDiscountAmount != null ? percentage.min(maxDiscountAmount) : percentage;
            }
            case FIXED_AMOUNT -> discountValue;
        };
        BigDecimal ceiling = subtotal.subtract(MIN_PAYABLE).max(BigDecimal.ZERO);
        return discount.min(ceiling).setScale(2, RoundingMode.HALF_UP);
    }

    public void recordRedemption() {
        usedCount++;
    }

    public void recordRelease() {
        if (usedCount > 0) {
            usedCount--;
        }
    }
}
