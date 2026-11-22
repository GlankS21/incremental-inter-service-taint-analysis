package com.shop.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The quantity of a product that can still be sold changed (reservation, release, return, manual
 * adjustment). product-service keeps an "in stock" flag from it for search ranking and listings;
 * consumers ignore events older than the last one applied ({@code occurredAt}).
 */
public record StockLevelChangedEvent(
        UUID eventId,
        Instant occurredAt,
        Long productId,
        int availableQuantity
) implements DomainEvent {
}
