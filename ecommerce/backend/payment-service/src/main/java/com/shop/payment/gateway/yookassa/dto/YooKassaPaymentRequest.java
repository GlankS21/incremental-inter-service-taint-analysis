package com.shop.payment.gateway.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Body of {@code POST /v3/payments}.
 *
 * @param capture     {@code true}: the payment is captured as soon as the customer confirms it
 * @param description shown to the customer, at most 128 characters
 * @param metadata    returned with the payment, at most 16 keys
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record YooKassaPaymentRequest(
        YooKassaAmount amount,
        boolean capture,
        YooKassaConfirmation confirmation,
        String description,
        Map<String, String> metadata
) {
}
