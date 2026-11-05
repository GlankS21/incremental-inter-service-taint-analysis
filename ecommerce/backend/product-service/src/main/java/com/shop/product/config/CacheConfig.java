package com.shop.product.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-memory caches of the search: type-ahead results per normalised query and the popular queries.
 * Both are cleared after every catalogue change (see {@code SearchIndexMaintenance}); their short
 * lifetime bounds the staleness of the stock and sales data they contain.
 */
@Configuration
public class CacheConfig {

    public static final String SEARCH_SUGGESTIONS = "searchSuggestions";
    public static final String POPULAR_SEARCHES = "popularSearches";

    @Bean
    public CacheManager cacheManager(SearchProperties properties) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setAllowNullValues(false);
        cacheManager.registerCustomCache(SEARCH_SUGGESTIONS, Caffeine.newBuilder()
                .maximumSize(properties.suggestCacheSize())
                .expireAfterWrite(properties.suggestCacheTtl())
                .build());
        cacheManager.registerCustomCache(POPULAR_SEARCHES, Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(properties.popularCacheTtl())
                .build());
        return cacheManager;
    }
}
