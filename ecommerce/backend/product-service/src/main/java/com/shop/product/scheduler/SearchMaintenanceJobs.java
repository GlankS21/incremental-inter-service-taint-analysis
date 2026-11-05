package com.shop.product.scheduler;

import com.shop.product.config.SearchProperties;
import com.shop.product.service.SearchHistoryService;
import com.shop.product.service.SearchIndexMaintenance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Periodic upkeep of the search: rebuild of the word dictionary after catalogue changes, and deletion of
 * the search log older than {@code app.search.history-retention}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchMaintenanceJobs {

    private final SearchIndexMaintenance searchIndexMaintenance;
    private final SearchHistoryService searchHistoryService;
    private final SearchProperties searchProperties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.search.dictionary-refresh-delay:PT30S}",
            initialDelayString = "${app.search.dictionary-refresh-delay:PT30S}")
    public void refreshDictionary() {
        searchIndexMaintenance.refreshDictionaryIfStale();
    }

    @Scheduled(cron = "${app.search.history-cleanup-cron:0 30 3 * * *}")
    public void purgeSearchHistory() {
        int deleted = searchHistoryService.purgeBefore(clock.instant().minus(searchProperties.historyRetention()));
        if (deleted > 0) {
            log.info("Deleted {} logged search(es) older than {}", deleted, searchProperties.historyRetention());
        }
    }
}
