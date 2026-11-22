package com.shop.product.dto.response;

import java.time.Instant;

/**
 * A review written by the caller, with its product.
 *
 * @param status PUBLISHED while the product is in the catalogue; HIDDEN when it was withdrawn (the
 *               review is kept but neither shown nor editable, only deletable)
 */
public record UserReviewResponse(
        Long id,
        Long productId,
        String productSlug,
        String productName,
        String productImageUrl,
        ReviewVisibility status,
        int rating,
        String comment,
        String authorName,
        boolean verifiedPurchase,
        Instant createdAt,
        Instant updatedAt
) {
}
