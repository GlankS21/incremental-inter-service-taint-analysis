package com.shop.product.dto.response;

import com.shop.product.dto.response.SearchFacetsResponse.CategoryFacet;

import java.util.List;

/**
 * Shown in an empty search box and on pages without results.
 *
 * @param queries    most frequent searches with results over the popularity window
 * @param categories best-selling categories, with their number of active products
 */
public record PopularSearchesResponse(
        List<String> queries,
        List<CategoryFacet> categories
) {
}
