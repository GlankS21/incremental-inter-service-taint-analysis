package com.shop.product.dto.response;

import java.time.Instant;

/**
 * Public view of a review: the author is identified by the display name only.
 *
 * @param verifiedPurchase the author received the product in a delivered order («Покупка подтверждена»)
 */
public record ReviewResponse(
        Long id,
        Long productId,
        String authorName,
        int rating,
        String comment,
        boolean verifiedPurchase,
        Instant createdAt,
        Instant updatedAt
) {
}
