package com.shop.product.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Terms that the search treats as interchangeable, in alphabetical order.
 */
public record SynonymGroupResponse(
        Long id,
        List<String> terms,
        Instant createdAt,
        Instant updatedAt
) {
}
