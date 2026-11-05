package com.shop.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The cart to price and the coupon to try on it; nothing is reserved.
 */
public record CouponPreviewRequest(

        @Schema(description = "Код промокода, регистр не учитывается", example = "WELCOME10")
        @NotBlank @Size(max = 30)
        String code,

        @Schema(description = "Товары корзины")
        @NotEmpty @Size(max = 100)
        List<@NotNull @Valid OrderItemRequest> items
) {
}
