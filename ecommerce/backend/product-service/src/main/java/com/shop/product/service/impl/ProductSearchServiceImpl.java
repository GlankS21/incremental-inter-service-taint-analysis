package com.shop.product.service.impl;

import com.shop.common.dto.PageResponse;
import com.shop.common.exception.BadRequestException;
import com.shop.product.config.CacheConfig;
import com.shop.product.config.CatalogProperties;
import com.shop.product.config.SearchProperties;
import com.shop.product.dto.request.ProductSearchCriteria;
import com.shop.product.dto.response.PopularSearchesResponse;
import com.shop.product.dto.response.ProductSearchResponse;
import com.shop.product.dto.response.ProductSummaryResponse;
import com.shop.product.dto.response.SearchFacetsResponse;
import com.shop.product.dto.response.SearchSuggestionsResponse;
import com.shop.product.dto.response.SuggestProductResponse;
import com.shop.product.entity.Product;
import com.shop.product.entity.ProductStatus;
import com.shop.product.mapper.ProductMapper;
import com.shop.product.repository.CategoryRepository;
import com.shop.product.repository.ProductRepository;
import com.shop.product.repository.ProductSearchRepository;
import com.shop.product.repository.SearchDictionaryRepository;
import com.shop.product.repository.SearchFilters;
import com.shop.product.repository.SearchPlan;
import com.shop.product.repository.SearchStatsRepository;
import com.shop.product.service.ProductSearchService;
import com.shop.product.service.ProductService;
import com.shop.product.service.SearchHistoryService;
import com.shop.product.service.SearchQueryPlanner;
import com.shop.product.util.SearchText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Search tiers of a keyword, stopping at the first one that finds products:
 * <ol>
 *     <li>full text with Russian stemming, synonyms and the last word as a prefix;</li>
 *     <li>when nothing matched: the query typed on the other keyboard layout, then its spelling correction
 *     (reported as {@code correctedKeyword});</li>
 *     <li>when fewer than {@code app.search.fallback-below-results} products matched: typo-tolerant
 *     trigram matches on names and brands are added.</li>
 * </ol>
 * Statements run in one read-only transaction, which carries the trigram thresholds.
 */
@Slf4j
@Service
public class ProductSearchServiceImpl implements ProductSearchService {

    private static final String POPULAR_KEY = "popular";

    private final ProductService productService;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductSearchRepository productSearchRepository;
    private final SearchDictionaryRepository dictionaryRepository;
    private final SearchStatsRepository statsRepository;
    private final SearchQueryPlanner planner;
    private final SearchHistoryService searchHistoryService;
    private final ProductMapper productMapper;
    private final CatalogProperties catalogProperties;
    private final SearchProperties searchProperties;
    private final CacheManager cacheManager;
    private final TransactionTemplate readOnlyTransaction;
    private final Clock clock;

    public ProductSearchServiceImpl(ProductService productService, ProductRepository productRepository,
                                    CategoryRepository categoryRepository,
                                    ProductSearchRepository productSearchRepository,
                                    SearchDictionaryRepository dictionaryRepository,
                                    SearchStatsRepository statsRepository, SearchQueryPlanner planner,
                                    SearchHistoryService searchHistoryService, ProductMapper productMapper,
                                    CatalogProperties catalogProperties, SearchProperties searchProperties,
                                    CacheManager cacheManager, PlatformTransactionManager transactionManager,
                                    Clock clock) {
        this.productService = productService;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productSearchRepository = productSearchRepository;
        this.dictionaryRepository = dictionaryRepository;
        this.statsRepository = statsRepository;
        this.planner = planner;
        this.searchHistoryService = searchHistoryService;
        this.productMapper = productMapper;
        this.catalogProperties = catalogProperties;
        this.searchProperties = searchProperties;
        this.cacheManager = cacheManager;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.clock = clock;
    }

    @Override
    public ProductSearchResponse search(ProductSearchCriteria criteria, Pageable pageable, boolean includeHidden) {
        validateSort(pageable.getSort());
        if (criteria.minPrice() != null && criteria.maxPrice() != null
                && criteria.minPrice().compareTo(criteria.maxPrice()) > 0) {
            throw new BadRequestException("product.error.price-range");
        }
        String keyword = normalizeQuery(criteria.keyword());
        ProductSearchResponse response = keyword == null
                ? readOnlyTransaction.execute(status -> browse(criteria, pageable, includeHidden))
                : readOnlyTransaction.execute(status -> searchByKeyword(keyword, criteria, pageable, includeHidden));
        if (keyword != null && !includeHidden && pageable.getPageNumber() == 0) {
            logSearch(keyword, response);
        }
        return response;
    }

    @Override
    public SearchSuggestionsResponse suggest(String query) {
        String normalized = normalizeQuery(query);
        if (normalized == null) {
            return SearchSuggestionsResponse.empty("");
        }
        return cache(CacheConfig.SEARCH_SUGGESTIONS).get(normalized,
                () -> readOnlyTransaction.execute(status -> computeSuggestions(normalized)));
    }

    @Override
    public PopularSearchesResponse popular() {
        return cache(CacheConfig.POPULAR_SEARCHES).get(POPULAR_KEY,
                () -> readOnlyTransaction.execute(status -> new PopularSearchesResponse(
                        statsRepository.popularQueries(popularSince(), POPULAR_QUERIES),
                        productSearchRepository.popularCategories(POPULAR_CATEGORIES))));
    }

    /**
     * Browsing without keyword: the specification query of {@link ProductService}; "relevance" means
     * best sellers first.
     */
    private ProductSearchResponse browse(ProductSearchCriteria criteria, Pageable pageable, boolean includeHidden) {
        Sort sort = browsingSort(pageable.getSort());
        PageResponse<ProductSummaryResponse> page = productService.searchProducts(criteria,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort), includeHidden);
        SearchFilters filters = filters(criteria, includeHidden);
        SearchFacetsResponse facets = filters == null ? SearchFacetsResponse.empty()
                : productSearchRepository.facets(null, filters);
        return ProductSearchResponse.of(page, null, null, false, facets);
    }

    private ProductSearchResponse searchByKeyword(String keyword, ProductSearchCriteria criteria, Pageable pageable,
                                                  boolean includeHidden) {
        SearchFilters filters = filters(criteria, includeHidden);
        if (filters == null) {
            return ProductSearchResponse.of(PageResponse.from(Page.empty(pageable)), keyword, null, false,
                    SearchFacetsResponse.empty());
        }
        Resolution resolution = resolve(keyword, filters);
        List<Long> ids = resolution.total() == 0 ? List.of() : productSearchRepository.findIds(resolution.plan(),
                filters, pageable.getSort(), pageable.getPageSize(), pageable.getOffset());
        List<ProductSummaryResponse> content = loadInOrder(ids).stream().map(this::toSummary).toList();
        PageResponse<ProductSummaryResponse> page = PageResponse.from(
                new PageImpl<>(content, pageable, resolution.total()));
        SearchFacetsResponse facets = productSearchRepository.facets(resolution.plan(), filters);
        return ProductSearchResponse.of(page, keyword, resolution.correctedQuery(),
                resolution.correctedQuery() != null, facets);
    }

    private SearchSuggestionsResponse computeSuggestions(String query) {
        SearchFilters filters = SearchFilters.publicCatalog();
        Resolution resolution = resolve(query, filters);
        List<SuggestProductResponse> products = List.of();
        if (resolution.total() > 0) {
            List<Long> ids = productSearchRepository.findIds(resolution.plan(), filters, Sort.unsorted(),
                    SUGGEST_PRODUCTS, 0);
            products = loadInOrder(ids).stream().map(this::toSuggestion).toList();
        }
        return new SearchSuggestionsResponse(query, resolution.correctedQuery(), products,
                resolution.total() == 0 ? List.of()
                        : productSearchRepository.categoryFacets(resolution.plan(), filters, SUGGEST_CATEGORIES),
                resolution.total() == 0 ? List.of()
                        : productSearchRepository.brandFacets(resolution.plan(), filters, SUGGEST_BRANDS),
                completions(query), resolution.total());
    }

    /**
     * Runs the tiers of the search (see the class documentation).
     */
    private Resolution resolve(String query, SearchFilters filters) {
        productSearchRepository.useSimilarityThresholds(searchProperties.wordSimilarityThreshold(),
                searchProperties.spellingSimilarityThreshold());
        String effectiveQuery = query;
        String correctedQuery = null;
        SearchPlan plan = planner.plan(query, true);
        long total = productSearchRepository.count(plan, filters);
        if (total == 0) {
            for (String correction : planner.corrections(query)) {
                SearchPlan correctedPlan = planner.plan(correction, false);
                long correctedTotal = productSearchRepository.count(correctedPlan, filters);
                if (correctedTotal > 0) {
                    effectiveQuery = correction;
                    correctedQuery = correction;
                    plan = correctedPlan;
                    total = correctedTotal;
                    break;
                }
            }
        }
        if (total < searchProperties.fallbackBelowResults()) {
            List<String> needles = planner.trigramNeedles(effectiveQuery);
            if (!needles.isEmpty()) {
                SearchPlan tolerant = plan.withTrigramNeedles(needles);
                long tolerantTotal = productSearchRepository.count(tolerant, filters);
                if (tolerantTotal > total) {
                    plan = tolerant;
                    total = tolerantTotal;
                }
            }
        }
        return new Resolution(plan, correctedQuery, total);
    }

    /**
     * Popular past searches extending the query, then the query completed with catalogue words starting
     * with its last word.
     */
    private List<String> completions(String query) {
        String canonical = SearchText.canonical(query);
        Set<String> completions = new LinkedHashSet<>(
                statsRepository.queriesStartingWith(canonical, popularSince(), SUGGEST_COMPLETIONS));
        List<String> tokens = SearchText.tokens(canonical);
        String last = tokens.getLast();
        if (completions.size() < SUGGEST_COMPLETIONS && last.length() >= SearchQueryPlanner.MIN_PREFIX_LENGTH) {
            String head = canonical.substring(0, canonical.length() - last.length());
            dictionaryRepository.wordsStartingWith(last, SUGGEST_COMPLETIONS)
                    .forEach(word -> completions.add(head + word));
        }
        completions.remove(canonical);
        return completions.stream().limit(SUGGEST_COMPLETIONS).toList();
    }

    /**
     * Filters of the criteria, {@code null} when the category does not exist (nothing can match).
     */
    private SearchFilters filters(ProductSearchCriteria criteria, boolean includeHidden) {
        List<Long> categoryIds = null;
        if (criteria.categoryId() != null) {
            categoryIds = categoryRepository.findIdsOfSelfAndChildren(criteria.categoryId());
            if (categoryIds.isEmpty()) {
                return null;
            }
        }
        ProductStatus status = includeHidden ? criteria.status() : ProductStatus.ACTIVE;
        return new SearchFilters(status, categoryIds, criteria.brand(), criteria.minPrice(), criteria.maxPrice());
    }

    private List<Product> loadInOrder(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> byId = productRepository.findAllByIdIn(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private void logSearch(String keyword, ProductSearchResponse response) {
        try {
            searchHistoryService.record(keyword, response.correctionApplied() ? response.correctedKeyword() : null,
                    response.totalElements());
        } catch (RuntimeException ex) {
            log.warn("Could not log the search \"{}\": {}", keyword, ex.getMessage());
        }
    }

    /**
     * Normalised query, {@code null} for a query without letters or digits.
     *
     * @throws BadRequestException for a query longer than {@link SearchText#MAX_QUERY_LENGTH}
     */
    private static String normalizeQuery(String query) {
        if (query != null && query.strip().length() > SearchText.MAX_QUERY_LENGTH) {
            throw new BadRequestException("product.error.keyword-too-long", String.valueOf(SearchText.MAX_QUERY_LENGTH));
        }
        return SearchText.normalize(query);
    }

    private static void validateSort(Sort sort) {
        sort.forEach(order -> {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new BadRequestException("product.error.unsupported-sort",
                        String.join(", ", SORTABLE_PROPERTIES.stream().sorted().toList()));
            }
        });
    }

    /**
     * Newest first by default; relevance has no meaning without keyword and orders by sales. Ties are
     * broken by id in {@link ProductService#searchProducts}.
     */
    private static Sort browsingSort(Sort sort) {
        if (sort.isUnsorted()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            orders.add(ProductSearchRepository.RELEVANCE.equals(order.getProperty())
                    ? Sort.Order.desc("salesCount")
                    : order);
        }
        return Sort.by(orders);
    }

    private Instant popularSince() {
        return clock.instant().minus(searchProperties.popularPeriod());
    }

    private Cache cache(String name) {
        return Objects.requireNonNull(cacheManager.getCache(name), name);
    }

    private ProductSummaryResponse toSummary(Product product) {
        return productMapper.toSummary(product, catalogProperties.currency());
    }

    private SuggestProductResponse toSuggestion(Product product) {
        ProductSummaryResponse summary = toSummary(product);
        return new SuggestProductResponse(summary.id(), summary.name(), summary.slug(), summary.brand(),
                summary.thumbnailUrl(), summary.price(), summary.compareAtPrice(), summary.currency(),
                summary.inStock());
    }

    private record Resolution(SearchPlan plan, String correctedQuery, long total) {
    }
}
