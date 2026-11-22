package com.shop.product.service;

import com.shop.product.dto.request.ProductSearchCriteria;
import com.shop.product.dto.response.PopularSearchesResponse;
import com.shop.product.dto.response.ProductSearchResponse;
import com.shop.product.dto.response.SearchSuggestionsResponse;
import com.shop.product.repository.ProductSearchRepository;
import org.springframework.data.domain.Pageable;

import java.util.HashSet;
import java.util.Set;

/**
 * Catalogue search: browsing with filters, keyword search with Russian morphology, synonyms, keyboard
 * layout and spelling corrections and typo tolerance, type-ahead suggestions and popular searches.
 */
public interface ProductSearchService {

    int SUGGEST_PRODUCTS = 5;
    int SUGGEST_CATEGORIES = 3;
    int SUGGEST_BRANDS = 3;
    int SUGGEST_COMPLETIONS = 5;
    int POPULAR_QUERIES = 10;
    int POPULAR_CATEGORIES = 6;

    /** Sort properties of {@link ProductService#SORTABLE_PROPERTIES} plus relevance. */
    Set<String> SORTABLE_PROPERTIES = sortableProperties();

    /**
     * Without {@code sort}, a keyword search is ordered by relevance and browsing by creation date (newest
     * first); {@code relevance} without keyword orders by sales. The first page of a customer's keyword
     * search is logged.
     */
    ProductSearchResponse search(ProductSearchCriteria criteria, Pageable pageable, boolean includeHidden);

    /**
     * Type-ahead preview of a query (at most {@code SearchText.MAX_QUERY_LENGTH} characters), cached
     * briefly per normalised query.
     */
    SearchSuggestionsResponse suggest(String query);

    PopularSearchesResponse popular();

    private static Set<String> sortableProperties() {
        Set<String> properties = new HashSet<>(ProductService.SORTABLE_PROPERTIES);
        properties.add(ProductSearchRepository.RELEVANCE);
        return Set.copyOf(properties);
    }
}
