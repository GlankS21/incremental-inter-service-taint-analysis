package com.shop.product.dto.response;

import com.shop.product.dto.response.SearchFacetsResponse.BrandFacet;
import com.shop.product.dto.response.SearchFacetsResponse.CategoryFacet;

import java.util.List;

/**
 * Type-ahead preview of a query, computed in one call.
 *
 * @param query          normalised query
 * @param correctedQuery query the products were found for when the typed one matched nothing (keyboard
 *                       layout, spelling), {@code null} otherwise
 * @param categories     categories of the matching products, with their number of matches
 * @param completions    completions of the query: popular past searches and catalogue words
 * @param totalProducts  number of matching products ("show all results")
 */
public record SearchSuggestionsResponse(
        String query,
        String correctedQuery,
        List<SuggestProductResponse> products,
        List<CategoryFacet> categories,
        List<BrandFacet> brands,
        List<String> completions,
        long totalProducts
) {

    public static SearchSuggestionsResponse empty(String query) {
        return new SearchSuggestionsResponse(query, null, List.of(), List.of(), List.of(), List.of(), 0);
    }
}
