package com.shop.payment.gateway.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * @param value amount as text with two decimals, e.g. {@code "1234.50"}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YooKassaAmount(String value, String currency) {

    public static YooKassaAmount of(BigDecimal amount, String currency) {
        return new YooKassaAmount(amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), currency);
    }

    public BigDecimal toBigDecimal() {
        return value == null ? null : new BigDecimal(value);
    }
}
