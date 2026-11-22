package com.shop.notification.entity;

/**
 * {@code PENDING} until the first delivery attempt. {@code SENDING} while one sender owns the attempt.
 * {@code FAILED} e-mails are retried until the maximum number of attempts is reached; an administrator
 * can resend them afterwards. {@code EXPIRED} e-mails held a link that expired before it was sent.
 */
public enum NotificationStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    EXPIRED
}
