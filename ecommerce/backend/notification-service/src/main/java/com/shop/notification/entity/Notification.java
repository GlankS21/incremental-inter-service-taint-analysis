package com.shop.notification.entity;

import com.shop.common.util.TextUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An e-mail rendered from one event. The unique event id makes event handling idempotent: a
 * redelivered event never sends the e-mail twice.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "notifications")
public class Notification extends AuditableEntity {

    static final int MAX_ERROR_LENGTH = 1000;
    /** Replaces a confidential body once it is no longer needed for delivery. */
    public static final String ERASED_BODY = "[erased]";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private NotificationType type;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String recipient;

    /** Order number for order notifications. */
    @Column(length = 100, updatable = false)
    private String reference;

    @Column(nullable = false, length = 255, updatable = false)
    private String subject;

    /** A confidential body is erased once sent or expired (see {@link NotificationType#hasConfidentialBody()}). */
    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** End of validity of the link in the e-mail; never sent afterwards. */
    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Version
    private Long version;

    public void markSent(Instant at) {
        attempts++;
        status = NotificationStatus.SENT;
        sentAt = at;
        lastError = null;
        eraseConfidentialBody();
    }

    public void markExpired() {
        status = NotificationStatus.EXPIRED;
        eraseConfidentialBody();
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    private void eraseConfidentialBody() {
        if (type != null && type.hasConfidentialBody()) {
            body = ERASED_BODY;
        }
    }

    public void markFailed(String error) {
        attempts++;
        status = NotificationStatus.FAILED;
        lastError = TextUtils.truncate(error, MAX_ERROR_LENGTH);
    }
}
