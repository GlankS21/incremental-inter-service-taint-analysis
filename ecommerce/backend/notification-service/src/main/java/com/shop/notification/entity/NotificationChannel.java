package com.shop.notification.entity;

/**
 * Where a notification reaches the customer.
 */
public enum NotificationChannel {

    /** Sent by e-mail (and listed in the account). */
    EMAIL,

    /** Only listed in the account ("Уведомления"): delivered when recorded, never e-mailed. */
    IN_APP
}
