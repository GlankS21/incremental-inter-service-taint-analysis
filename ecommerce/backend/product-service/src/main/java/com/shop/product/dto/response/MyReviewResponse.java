package com.shop.product.dto.response;

import java.time.Instant;

/**
 * Review state of a product for the caller.
 *
 * @param purchased   whether the caller received the product in a delivered order
 * @param canReview   whether the caller may create (or edit) a review; equals {@code purchased}
 * @param purchasedAt delivery of the latest order containing the product, {@code null} when not purchased
 * @param review      the caller's review, {@code null} when there is none
 */
public record MyReviewResponse(
        boolean purchased,
        boolean canReview,
        Instant purchasedAt,
        ReviewResponse review
) {
}
