package com.shop.payment.entity;

import com.shop.common.util.TextUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A notification of the payment provider with its original body. The body is kept for the record
 * only: the state of the notified object is always read again from the provider's API.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "payment_notifications")
public class PaymentNotification {

    static final int MAX_ERROR_LENGTH = 500;

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private PaymentProvider provider;

    @Column(nullable = false, length = 64, updatable = false)
    private String event;

    @Column(name = "object_id", nullable = false, length = 100, updatable = false)
    private String objectId;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    /** The charge or refund the notification was about, once identified. */
    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(length = MAX_ERROR_LENGTH)
    private String error;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    public static PaymentNotification received(PaymentProvider provider, String event, String objectId,
                                               String payload, Instant at) {
        PaymentNotification notification = new PaymentNotification();
        notification.id = UUID.randomUUID();
        notification.provider = provider;
        notification.event = event;
        notification.objectId = objectId;
        notification.payload = payload;
        notification.status = NotificationStatus.RECEIVED;
        notification.receivedAt = at;
        return notification;
    }

    public void finish(NotificationStatus outcome, UUID transaction, String failure, Instant at) {
        status = outcome;
        transactionId = transaction;
        error = TextUtils.truncate(failure, MAX_ERROR_LENGTH);
        processedAt = at;
    }
}
