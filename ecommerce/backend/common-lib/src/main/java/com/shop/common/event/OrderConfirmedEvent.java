package com.shop.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Saga completed successfully: the order is PAID. inventory-service commits the reservation.
 * {@code paymentMethod} is the name of the order's payment method (COD, ONLINE): a cash-on-delivery order
 * is confirmed without having been paid yet; {@code null} in events published before it was added.
 */
public record OrderConfirmedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        String orderNumber,
        UUID userId,
        String userEmail,
        BigDecimal totalAmount,
        String currency,
        String paymentMethod
) implements DomainEvent {
}
