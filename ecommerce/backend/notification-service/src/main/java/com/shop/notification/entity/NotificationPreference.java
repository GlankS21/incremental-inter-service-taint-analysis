package com.shop.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Notification settings of a customer. Written with an upsert
 * ({@code NotificationPreferenceRepository#upsert}); a customer without a row has the defaults.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    /** Default of {@link #reviewReminders} for customers who never changed it. */
    public static final boolean DEFAULT_REVIEW_REMINDERS = true;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    /** Whether the review reminder e-mail is sent. */
    @Column(name = "review_reminders", nullable = false)
    private boolean reviewReminders;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
