package com.shop.payment.gateway.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Confirmation scenario. Requests use {@code embedded} (the YooKassa widget on the shop's page);
 * responses carry the token the widget is initialised with.
 *
 * @param locale language of the widget, {@code ru_RU} or {@code en_US}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record YooKassaConfirmation(
        String type,
        String locale,
        @JsonProperty("confirmation_token") String confirmationToken
) {

    public static final String EMBEDDED = "embedded";
    public static final String RUSSIAN = "ru_RU";

    public static YooKassaConfirmation embedded(String locale) {
        return new YooKassaConfirmation(EMBEDDED, locale, null);
    }
}
