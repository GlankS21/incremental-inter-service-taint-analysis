package com.shop.order.repository;

import com.shop.order.entity.Coupon;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Optional filters of the coupon administration list. A {@code null} filter matches everything.
 */
public final class CouponSpecifications {

    private CouponSpecifications() {
    }

    public static Specification<Coupon> isActive(Boolean active) {
        return (root, query, cb) -> active == null ? null : cb.equal(root.get("active"), active);
    }

    /**
     * Part of the code or of the description, case-insensitive.
     */
    public static Specification<Coupon> matchesKeyword(String keyword) {
        return (root, query, cb) -> {
            if (!StringUtils.hasText(keyword)) {
                return null;
            }
            String pattern = "%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("code")), pattern, '\\'),
                    cb.like(cb.lower(root.get("description")), pattern, '\\'));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
