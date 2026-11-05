package com.shop.payment.gateway.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Refund object of API v3.
 *
 * @param status {@code pending}, {@code succeeded} or {@code canceled}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YooKassaRefund(
        String id,
        @JsonProperty("payment_id") String paymentId,
        String status,
        YooKassaAmount amount,
        @JsonProperty("cancellation_details") YooKassaCancellationDetails cancellationDetails
) {
}
