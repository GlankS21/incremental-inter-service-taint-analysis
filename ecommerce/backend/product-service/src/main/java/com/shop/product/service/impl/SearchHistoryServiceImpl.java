package com.shop.product.service.impl;

import com.shop.common.exception.BadRequestException;
import com.shop.common.util.TextUtils;
import com.shop.product.dto.response.SearchStatsResponse;
import com.shop.product.entity.SearchQuery;
import com.shop.product.repository.SearchQueryRepository;
import com.shop.product.repository.SearchStatsRepository;
import com.shop.product.service.SearchHistoryService;
import com.shop.product.util.SearchText;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchHistoryServiceImpl implements SearchHistoryService {

    private final SearchQueryRepository searchQueryRepository;
    private final SearchStatsRepository searchStatsRepository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String query, String correctedQuery, long resultsCount) {
        try {
            SearchQuery entry = new SearchQuery();
            entry.setQuery(truncate(query));
            entry.setCorrectedQuery(correctedQuery == null ? null : truncate(correctedQuery));
            entry.setResultsCount((int) Math.min(resultsCount, Integer.MAX_VALUE));
            entry.setSearchedAt(clock.instant());
            searchQueryRepository.save(entry);
        } catch (RuntimeException ex) {
            log.warn("Could not log the search \"{}\": {}", query, ex.getMessage());
        }
    }

    @Override
    @Transactional
    public int purgeBefore(Instant before) {
        return searchQueryRepository.deleteSearchedBefore(before);
    }

    @Override
    @Transactional(readOnly = true)
    public SearchStatsResponse stats(int days, int limit) {
        if (days < 1 || days > MAX_STATS_DAYS) {
            throw new BadRequestException("search.error.stats-days", String.valueOf(MAX_STATS_DAYS));
        }
        if (limit < 1 || limit > MAX_STATS_LIMIT) {
            throw new BadRequestException("search.error.stats-limit", String.valueOf(MAX_STATS_LIMIT));
        }
        Instant to = clock.instant();
        Instant from = to.minus(Duration.ofDays(days));
        SearchStatsRepository.Totals totals = searchStatsRepository.totals(from, to);
        double zeroResultRate = totals.searches() == 0 ? 0 : (double) totals.zeroResultSearches() / totals.searches();
        return new SearchStatsResponse(from, to, totals.searches(), totals.uniqueQueries(),
                totals.zeroResultSearches(), zeroResultRate,
                searchStatsRepository.topQueries(from, to, limit),
                searchStatsRepository.zeroResultQueries(from, to, limit));
    }

    private static String truncate(String value) {
        return TextUtils.truncate(value, SearchText.MAX_QUERY_LENGTH);
    }
}
