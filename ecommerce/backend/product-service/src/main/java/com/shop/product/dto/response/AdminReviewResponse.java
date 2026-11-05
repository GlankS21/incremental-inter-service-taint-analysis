package com.shop.product.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Moderation view of a review, with its product and the account of the author.
 */
public record AdminReviewResponse(
        Long id,
        Long productId,
        String productName,
        String productSlug,
        UUID userId,
        String authorName,
        int rating,
        String comment,
        boolean verifiedPurchase,
        Instant createdAt,
        Instant updatedAt
) {
}
