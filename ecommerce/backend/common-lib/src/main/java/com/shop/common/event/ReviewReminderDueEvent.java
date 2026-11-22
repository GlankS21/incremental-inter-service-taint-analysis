package com.shop.common.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Some products of a delivered order are still not reviewed a few days after delivery. Published once
 * per order by product-service; notification-service e-mails the customer one reminder.
 * <p>
 * The event id is derived from the order id, so a republished reminder is recognised as the same event.
 *
 * @param items the products of the order the customer has not reviewed yet, never empty
 */
public record ReviewReminderDueEvent(
        UUID eventId,
        Instant occurredAt,
        UUID userId,
        UUID orderId,
        String orderNumber,
        List<ReviewReminderItem> items
) implements DomainEvent {
}
