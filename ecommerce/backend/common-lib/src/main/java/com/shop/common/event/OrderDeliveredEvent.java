package com.shop.common.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The order was DELIVERED. product-service records the purchased products, which lets the customer
 * review them; notification-service asks the customer (in the app) to rate the purchase.
 *
 * @param userEmail address the order e-mails go to, used later for the review reminder; {@code null} in
 *                  events published before it was added
 */
public record OrderDeliveredEvent(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        String orderNumber,
        UUID userId,
        String userEmail,
        List<OrderItemPayload> items
) implements DomainEvent {
}
