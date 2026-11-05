package com.shop.order.dto.request;

import com.shop.order.entity.Coupon;
import com.shop.order.entity.CouponDiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Creation or full replacement of a coupon. Cross-field rules (percentage at most 100, period) are
 * checked by the service; {@code maxDiscountAmount} only applies to percentages and is cleared for
 * fixed amounts.
 */
public record CouponRequest(

        @Schema(description = "От 3 до 30 латинских букв, цифр, «-» или «_»; сохраняется в верхнем регистре", example = "WELCOME10")
        @NotBlank @Pattern(regexp = Coupon.CODE_PATTERN, message = "{coupon.validation.code}")
        String code,

        @Schema(description = "Описание промокода", example = "Скидка 10 % на первый заказ")
        @Size(max = 255)
        String description,

        @Schema(description = "Тип скидки: процент или фиксированная сумма", example = "PERCENTAGE")
        @NotNull
        CouponDiscountType discountType,

        @Schema(description = "Процент (не более 100) или сумма в валюте каталога (RUB)", example = "10")
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
        BigDecimal discountValue,

        @Schema(description = "Минимальная сумма товаров в заказе, ₽", example = "5000")
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
        BigDecimal minOrderAmount,

        @Schema(description = "Максимальная скидка в процентах, ₽; для фиксированной суммы не учитывается", example = "5000")
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
        BigDecimal maxDiscountAmount,

        @Schema(description = "Начало действия; пусто — действует сразу")
        Instant startsAt,

        @Schema(description = "Окончание действия (не включая этот момент); должно быть позже startsAt")
        Instant expiresAt,

        @Schema(description = "Общий лимит использований без учёта отменённых заказов; пусто — без ограничений")
        @Min(1)
        Integer usageLimit,

        @Schema(description = "Лимит использований одним покупателем без учёта отменённых заказов; пусто — без ограничений")
        @Min(1)
        Integer perUserLimit,

        @Schema(description = "Промокод включён", example = "true")
        @NotNull
        Boolean active
) {
}
