package com.shop.product.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Search log statistics of a period, for administrators.
 *
 * @param zeroResultRate share of the searches without any result, from 0 to 1
 */
public record SearchStatsResponse(
        Instant from,
        Instant to,
        long totalSearches,
        long uniqueQueries,
        long zeroResultSearches,
        double zeroResultRate,
        List<QueryStat> topQueries,
        List<ZeroResultQueryStat> zeroResultQueries
) {

    public record QueryStat(String query, long searches, double averageResults, Instant lastSearchedAt) {
    }

    public record ZeroResultQueryStat(String query, long searches, Instant lastSearchedAt) {
    }
}
