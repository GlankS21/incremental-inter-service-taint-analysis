package com.shop.common.event;

/**
 * A product the customer is reminded to review.
 */
public record ReviewReminderItem(
        Long productId,
        String productName,
        String productSlug
) {
}
