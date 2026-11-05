package com.shop.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Compensation completed: the payment of a cancelled order was returned to the customer.
 */
public record PaymentRefundedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID paymentId,
        UUID orderId,
        String orderNumber,
        String refundId,
        BigDecimal amount,
        String currency
) implements DomainEvent {
}
