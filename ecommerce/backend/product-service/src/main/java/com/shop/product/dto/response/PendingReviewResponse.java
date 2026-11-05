package com.shop.product.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * A product the caller received and has not reviewed yet.
 *
 * @param imageUrl    first image of the product, {@code null} when it has none
 * @param orderId     the latest delivered order containing the product
 * @param deliveredAt when that order was delivered
 */
public record PendingReviewResponse(
        Long productId,
        String slug,
        String name,
        String imageUrl,
        UUID orderId,
        Instant deliveredAt
) {
}
