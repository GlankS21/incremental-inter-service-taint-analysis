package com.shop.product.service;

import com.shop.product.config.CacheConfig;
import com.shop.product.repository.SearchDictionaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the derived search data in line with the catalogue: after a committed change of products,
 * categories, synonyms or stock, the search caches are cleared and the word dictionary is marked stale;
 * the scheduler rebuilds it shortly after ({@link #refreshDictionaryIfStale}), so that a burst of edits
 * costs one refresh. The full-text documents themselves are maintained by database triggers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexMaintenance {

    private final SearchDictionaryRepository dictionaryRepository;
    private final CacheManager cacheManager;
    private final AtomicBoolean dictionaryStale = new AtomicBoolean();

    /**
     * Takes effect after the commit of the current transaction (immediately without one): a rolled-back
     * change leaves the caches alone, and a cache cannot be refilled with the old data meanwhile.
     */
    public void catalogChanged() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate();
                }
            });
        } else {
            invalidate();
        }
    }

    public void refreshDictionaryIfStale() {
        if (!dictionaryStale.getAndSet(false)) {
            return;
        }
        try {
            dictionaryRepository.refresh();
            log.debug("Search dictionary refreshed");
        } catch (RuntimeException ex) {
            dictionaryStale.set(true);
            log.warn("Could not refresh the search dictionary, will retry: {}", ex.getMessage());
        }
    }

    private void invalidate() {
        dictionaryStale.set(true);
        clear(CacheConfig.SEARCH_SUGGESTIONS);
        clear(CacheConfig.POPULAR_SEARCHES);
    }

    private void clear(String cacheName) {
        Optional.ofNullable(cacheManager.getCache(cacheName)).ifPresent(Cache::clear);
    }
}
