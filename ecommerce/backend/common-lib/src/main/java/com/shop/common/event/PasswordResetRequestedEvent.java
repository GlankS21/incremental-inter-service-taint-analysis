package com.shop.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer asked to reset a forgotten password. notification-service e-mails the reset link.
 * <p>
 * {@link #token()} is the raw single-use token (auth-service only stores its hash); it travels on
 * the internal broker only and must never be logged.
 */
public record PasswordResetRequestedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID userId,
        String email,
        String token,
        Instant expiresAt
) implements DomainEvent {
}
