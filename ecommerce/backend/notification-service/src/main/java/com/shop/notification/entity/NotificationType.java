package com.shop.notification.entity;

/**
 * What triggered the notification.
 */
public enum NotificationType {

    /** {@code user.registered} */
    WELCOME,

    /** {@code user.password-reset-requested}: the body holds a single-use reset link. */
    PASSWORD_RESET(true, NotificationChannel.EMAIL),

    /** {@code order.created} */
    ORDER_PLACED,

    /** {@code order.confirmed}: the order is paid. */
    ORDER_CONFIRMED,

    /** {@code order.cancelled} */
    ORDER_CANCELLED,

    /** {@code order.delivered}: «Оцените покупку», shown in the account only. */
    REVIEW_REQUEST(false, NotificationChannel.IN_APP),

    /** {@code review.reminder-due}: the products of a delivered order still not reviewed, once per order. */
    REVIEW_REMINDER;

    private final boolean confidentialBody;
    private final NotificationChannel channel;

    NotificationType() {
        this(false, NotificationChannel.EMAIL);
    }

    NotificationType(boolean confidentialBody, NotificationChannel channel) {
        this.confidentialBody = confidentialBody;
        this.channel = channel;
    }

    /**
     * Whether the body holds a secret (such as a password reset link) and must not be returned by the
     * API. It is stored only until the e-mail is sent or expires, which also means it cannot be resent.
     */
    public boolean hasConfidentialBody() {
        return confidentialBody;
    }

    public NotificationChannel channel() {
        return channel;
    }
}
