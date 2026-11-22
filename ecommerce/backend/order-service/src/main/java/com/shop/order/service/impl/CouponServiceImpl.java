package com.shop.order.service.impl;

import com.shop.common.dto.PageResponse;
import com.shop.common.exception.BadRequestException;
import com.shop.common.exception.BusinessException;
import com.shop.common.exception.ConflictException;
import com.shop.common.exception.ResourceNotFoundException;
import com.shop.order.dto.request.CouponRequest;
import com.shop.order.dto.response.CouponResponse;
import com.shop.order.entity.Coupon;
import com.shop.order.entity.CouponDiscountType;
import com.shop.order.mapper.CouponMapper;
import com.shop.order.repository.CouponRedemptionRepository;
import com.shop.order.repository.CouponRepository;
import com.shop.order.repository.CouponSpecifications;
import com.shop.order.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private static final BigDecimal MAX_PERCENTAGE = BigDecimal.valueOf(100);

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository redemptionRepository;
    private final CouponMapper couponMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CouponResponse> searchCoupons(String keyword, Boolean active, Pageable pageable) {
        Specification<Coupon> specification = Specification.allOf(
                CouponSpecifications.matchesKeyword(keyword),
                CouponSpecifications.isActive(active));
        return PageResponse.from(couponRepository.findAll(specification, pageable), couponMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public CouponResponse getCoupon(Long couponId) {
        return couponMapper.toResponse(find(couponId));
    }

    @Override
    @Transactional
    public CouponResponse createCoupon(CouponRequest request) {
        validate(request);
        String code = Coupon.normalizeCode(request.code());
        if (couponRepository.existsByCode(code)) {
            throw new ConflictException("coupon.error.duplicate-code", code);
        }
        Coupon coupon = new Coupon();
        coupon.setCode(code);
        apply(request, coupon);
        coupon = couponRepository.saveAndFlush(coupon);
        log.info("Coupon {} created", coupon.getCode());
        return couponMapper.toResponse(coupon);
    }

    /**
     * Under the row lock of the coupon, so that a concurrent checkout never makes the update fail.
     */
    @Override
    @Transactional
    public CouponResponse updateCoupon(Long couponId, CouponRequest request) {
        validate(request);
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> ResourceNotFoundException.of("coupon.error.not-found"));
        String code = Coupon.normalizeCode(request.code());
        if (couponRepository.existsByCodeAndIdNot(code, couponId)) {
            throw new ConflictException("coupon.error.duplicate-code", code);
        }
        coupon.setCode(code);
        apply(request, coupon);
        coupon = couponRepository.saveAndFlush(coupon);
        log.info("Coupon {} updated", coupon.getCode());
        return couponMapper.toResponse(coupon);
    }

    @Override
    @Transactional
    public void deleteCoupon(Long couponId) {
        Coupon coupon = find(couponId);
        if (redemptionRepository.existsByCouponId(couponId)) {
            throw new BusinessException("coupon.error.in-use", coupon.getCode());
        }
        couponRepository.delete(coupon);
        log.info("Coupon {} deleted", coupon.getCode());
    }

    private Coupon find(Long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(() -> ResourceNotFoundException.of("coupon.error.not-found"));
    }

    /**
     * Rules spanning several fields; single-field rules are declared on {@link CouponRequest}.
     */
    private static void validate(CouponRequest request) {
        if (request.discountType() == CouponDiscountType.PERCENTAGE
                && request.discountValue().compareTo(MAX_PERCENTAGE) > 0) {
            throw new BadRequestException("coupon.error.percentage-range");
        }
        if (request.startsAt() != null && request.expiresAt() != null
                && !request.expiresAt().isAfter(request.startsAt())) {
            throw new BadRequestException("coupon.error.period");
        }
    }

    /**
     * The cap only applies to percentages; it is cleared for fixed amounts.
     */
    private void apply(CouponRequest request, Coupon coupon) {
        couponMapper.update(request, coupon);
        coupon.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : null);
        if (request.discountType() == CouponDiscountType.FIXED_AMOUNT) {
            coupon.setMaxDiscountAmount(null);
        }
    }
}
