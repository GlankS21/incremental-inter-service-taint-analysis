package com.shop.product.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning of the catalogue search ({@code app.search.*}); every value has a default.
 *
 * @param fallbackBelowResults       full-text results below which typo-tolerant (trigram) matches are added
 * @param wordSimilarityThreshold    pg_trgm word similarity a product name or brand needs to match a query word
 * @param spellingSimilarityThreshold pg_trgm similarity a dictionary word needs to replace a misspelt word
 * @param suggestCacheTtl            lifetime of cached type-ahead results
 * @param suggestCacheSize           number of cached type-ahead queries
 * @param popularCacheTtl            lifetime of the cached popular queries and categories
 * @param popularPeriod              window of the search log used for popular queries and completions
 * @param historyRetention           age after which logged searches are deleted
 */
@ConfigurationProperties(prefix = "app.search")
public record SearchProperties(
        Integer fallbackBelowResults,
        Double wordSimilarityThreshold,
        Double spellingSimilarityThreshold,
        Duration suggestCacheTtl,
        Long suggestCacheSize,
        Duration popularCacheTtl,
        Duration popularPeriod,
        Duration historyRetention
) {

    public SearchProperties {
        fallbackBelowResults = fallbackBelowResults == null ? 3 : fallbackBelowResults;
        wordSimilarityThreshold = wordSimilarityThreshold == null ? 0.45 : wordSimilarityThreshold;
        spellingSimilarityThreshold = spellingSimilarityThreshold == null ? 0.4 : spellingSimilarityThreshold;
        suggestCacheTtl = suggestCacheTtl == null ? Duration.ofSeconds(60) : suggestCacheTtl;
        suggestCacheSize = suggestCacheSize == null ? 10_000L : suggestCacheSize;
        popularCacheTtl = popularCacheTtl == null ? Duration.ofMinutes(5) : popularCacheTtl;
        popularPeriod = popularPeriod == null ? Duration.ofDays(30) : popularPeriod;
        historyRetention = historyRetention == null ? Duration.ofDays(180) : historyRetention;
    }
}
