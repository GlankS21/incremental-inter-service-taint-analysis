package com.shop.product.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Counts of the matching products by category, brand and price range. Each facet ignores its own filter
 * (the brand counts are computed without the brand filter...) so that the other values stay selectable.
 *
 * @param priceRanges non-empty ranges only; {@code from} inclusive, {@code to} exclusive, {@code null} = open
 * @param minPrice    cheapest matching product, ignoring the price filter
 * @param maxPrice    most expensive matching product, ignoring the price filter
 */
public record SearchFacetsResponse(
        List<CategoryFacet> categories,
        List<BrandFacet> brands,
        List<PriceRangeFacet> priceRanges,
        BigDecimal minPrice,
        BigDecimal maxPrice
) {

    public static SearchFacetsResponse empty() {
        return new SearchFacetsResponse(List.of(), List.of(), List.of(), null, null);
    }

    public record CategoryFacet(Long id, String name, String slug, long count) {
    }

    public record BrandFacet(String name, long count) {
    }

    public record PriceRangeFacet(BigDecimal from, BigDecimal to, long count) {
    }
}
