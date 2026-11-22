package com.shop.product.dto.response;

import java.math.BigDecimal;

/**
 * Product line of the type-ahead preview.
 */
public record SuggestProductResponse(
        Long id,
        String name,
        String slug,
        String brand,
        String thumbnailUrl,
        BigDecimal price,
        BigDecimal compareAtPrice,
        String currency,
        boolean inStock
) {
}
