package com.shop.payment.gateway.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of an HTTP notification (webhook). Only the event and the object id are used: the state of the
 * object is always read again from the API, never trusted from the notification.
 *
 * @param type  always {@code notification}
 * @param event e.g. {@code payment.succeeded}, {@code payment.canceled}, {@code refund.succeeded}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YooKassaNotification(String type, String event, NotificationObject object) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NotificationObject(String id) {
    }
}
