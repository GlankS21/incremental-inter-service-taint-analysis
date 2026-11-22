package com.shop.product.dto.response;

/**
 * Review state of one product for the caller (several products are asked at once).
 *
 * @param purchased whether the caller received the product in a delivered order
 * @param canReview whether the caller may write or edit a review now: purchased and still in the catalogue
 * @param reviewed  whether the caller has reviewed the product
 * @param rating    stars of the caller's review, {@code null} when not reviewed
 */
public record ReviewStatusResponse(
        Long productId,
        boolean purchased,
        boolean canReview,
        boolean reviewed,
        Integer rating
) {
}
