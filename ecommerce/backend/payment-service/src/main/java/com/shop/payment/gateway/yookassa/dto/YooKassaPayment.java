package com.shop.payment.gateway.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Payment object of API v3 (only the fields used by the shop).
 *
 * @param status {@code pending}, {@code waiting_for_capture}, {@code succeeded} or {@code canceled}
 * @param test   {@code true} for a payment of a test shop
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YooKassaPayment(
        String id,
        String status,
        Boolean paid,
        YooKassaAmount amount,
        YooKassaConfirmation confirmation,
        @JsonProperty("cancellation_details") YooKassaCancellationDetails cancellationDetails,
        Map<String, String> metadata,
        Boolean test
) {
}
