package com.shop.order.controller;

import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import com.shop.common.dto.ApiResponse;
import com.shop.common.dto.PageResponse;
import com.shop.order.dto.request.CouponRequest;
import com.shop.order.dto.response.CouponResponse;
import com.shop.order.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Success messages are resolved from {@code messages_ru.properties}.
 */
@Tag(name = "Управление промокодами", description = "Промокоды на скидку при оформлении заказа (только для администраторов)")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/orders/admin/coupons")
@RequiredArgsConstructor
public class AdminCouponController {

    private final CouponService couponService;
    private final MessageSource messageSource;

    @Operation(summary = "Поиск промокодов, сначала новые")
    @GetMapping
    public ApiResponse<PageResponse<CouponResponse>> search(
            @Parameter(description = "Часть кода или описания промокода")
            @RequestParam(name = "keyword", required = false) String keyword,
            @Parameter(description = "Только активные (true) или только отключённые (false)")
            @RequestParam(name = "active", required = false) Boolean active,
            @ParameterObject
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(couponService.searchCoupons(keyword, active, pageable));
    }

    @Operation(summary = "Промокод")
    @GetMapping("/{couponId}")
    public ApiResponse<CouponResponse> get(@PathVariable("couponId") Long couponId) {
        return ApiResponse.success(couponService.getCoupon(couponId));
    }

    @Operation(summary = "Создать промокод", description = "Код сохраняется в верхнем регистре и должен быть уникальным.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CouponResponse> create(@Valid @RequestBody CouponRequest request) {
        return ApiResponse.success(message("coupon.created"), couponService.createCoupon(request));
    }

    @Operation(summary = "Изменить промокод", description = "Счётчик использований сохраняется.")
    @PutMapping("/{couponId}")
    public ApiResponse<CouponResponse> update(@PathVariable("couponId") Long couponId,
                                              @Valid @RequestBody CouponRequest request) {
        return ApiResponse.success(message("coupon.updated"), couponService.updateCoupon(couponId, request));
    }

    @Operation(summary = "Удалить промокод, который ни разу не использовался",
            description = "Промокод, применённый хотя бы в одном заказе (даже отменённом), удалить нельзя — "
                    + "запрос отклоняется с кодом 422; такой промокод нужно отключить.")
    @DeleteMapping("/{couponId}")
    public ApiResponse<Void> delete(@PathVariable("couponId") Long couponId) {
        couponService.deleteCoupon(couponId);
        return ApiResponse.message(message("coupon.deleted"));
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, CommonMessagesAutoConfiguration.DEFAULT_LOCALE);
    }
}
