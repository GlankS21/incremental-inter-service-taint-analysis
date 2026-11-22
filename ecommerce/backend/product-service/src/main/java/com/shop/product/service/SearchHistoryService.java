package com.shop.product.service;

import com.shop.product.dto.response.SearchStatsResponse;

import java.time.Instant;

/**
 * Log of customer searches and its statistics.
 */
public interface SearchHistoryService {

    int MAX_STATS_DAYS = 365;
    int MAX_STATS_LIMIT = 100;

    /**
     * Records a search in its own transaction; a failure is logged and never reaches the search itself.
     *
     * @param correctedQuery the correction the results were found for, {@code null} if none
     */
    void record(String query, String correctedQuery, long resultsCount);

    /**
     * @return number of deleted entries
     */
    int purgeBefore(Instant before);

    /**
     * Statistics of the last {@code days} days (1 to {@link #MAX_STATS_DAYS}), lists limited to {@code limit}
     * queries (1 to {@link #MAX_STATS_LIMIT}).
     */
    SearchStatsResponse stats(int days, int limit);
}
