package com.shop.product.dto.response;

import java.util.Map;
import java.util.UUID;

/**
 * Number of products waiting for the caller's review.
 *
 * @param count   all products received and not reviewed (each product once)
 * @param byOrder for the orders asked with {@code orderIds}: products of the order not reviewed yet;
 *                orders without any are absent. Empty when no order was asked.
 */
public record PendingReviewCountResponse(
        long count,
        Map<UUID, Long> byOrder
) {
}
