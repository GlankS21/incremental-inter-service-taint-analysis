package com.shop.order.mapper;

import com.shop.order.dto.request.CouponRequest;
import com.shop.order.dto.response.CouponResponse;
import com.shop.order.entity.Coupon;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper
public interface CouponMapper {

    CouponResponse toResponse(Coupon coupon);

    /**
     * Copies the editable fields; the code is normalized by the service, the counters are never
     * set from a request.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "usedCount", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void update(CouponRequest request, @MappingTarget Coupon coupon);
}
