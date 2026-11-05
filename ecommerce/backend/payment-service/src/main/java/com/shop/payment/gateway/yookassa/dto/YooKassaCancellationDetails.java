package com.shop.payment.gateway.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * @param party  who cancelled: {@code yoo_money}, {@code payment_network} or {@code merchant}
 * @param reason e.g. {@code insufficient_funds}, {@code expired_on_confirmation}, {@code card_expired}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YooKassaCancellationDetails(String party, String reason) {
}
