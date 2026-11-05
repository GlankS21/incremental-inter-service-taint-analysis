package com.shop.product.dto.response;

import com.shop.common.dto.PageResponse;

import java.util.List;

/**
 * A page of the catalogue (the fields of {@link PageResponse}) plus what the search did with the keyword
 * and the facets of the matching products.
 *
 * @param keyword           normalised keyword, {@code null} without keyword
 * @param correctedKeyword  correction of a keyword that matched nothing as typed (keyboard layout,
 *                          spelling), {@code null} when none was needed or found
 * @param correctionApplied the content is the result of {@code correctedKeyword}
 */
public record ProductSearchResponse(
        List<ProductSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        String keyword,
        String correctedKeyword,
        boolean correctionApplied,
        SearchFacetsResponse facets
) {

    public static ProductSearchResponse of(PageResponse<ProductSummaryResponse> page, String keyword,
                                           String correctedKeyword, boolean correctionApplied,
                                           SearchFacetsResponse facets) {
        return new ProductSearchResponse(page.getContent(), page.getPage(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.isFirst(), page.isLast(), keyword, correctedKeyword, correctionApplied,
                facets);
    }
}
