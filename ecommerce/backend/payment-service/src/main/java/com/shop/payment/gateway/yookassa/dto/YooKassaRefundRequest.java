package com.shop.payment.gateway.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /v3/refunds}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record YooKassaRefundRequest(
        @JsonProperty("payment_id") String paymentId,
        YooKassaAmount amount,
        String description
) {
}
