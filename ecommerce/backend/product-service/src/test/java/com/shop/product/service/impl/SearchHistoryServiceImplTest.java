package com.shop.product.service.impl;

import com.shop.common.exception.BadRequestException;
import com.shop.product.dto.response.SearchStatsResponse;
import com.shop.product.entity.SearchQuery;
import com.shop.product.repository.SearchQueryRepository;
import com.shop.product.repository.SearchStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchHistoryServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    @Mock
    private SearchQueryRepository searchQueryRepository;
    @Mock
    private SearchStatsRepository searchStatsRepository;

    private SearchHistoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SearchHistoryServiceImpl(searchQueryRepository, searchStatsRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordsTheQueryItsCorrectionAndResultCount() {
        service.record("самсунк", "samsung", 3);

        ArgumentCaptor<SearchQuery> saved = ArgumentCaptor.forClass(SearchQuery.class);
        verify(searchQueryRepository).save(saved.capture());
        assertThat(saved.getValue().getQuery()).isEqualTo("самсунк");
        assertThat(saved.getValue().getCorrectedQuery()).isEqualTo("samsung");
        assertThat(saved.getValue().getResultsCount()).isEqualTo(3);
        assertThat(saved.getValue().getSearchedAt()).isEqualTo(NOW);
    }

    @Test
    void aFailureToLogDoesNotReachTheSearch() {
        when(searchQueryRepository.save(any())).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> service.record("телефон", null, 0)).doesNotThrowAnyException();
    }

    @Test
    void statsCoverTheRequestedDays() {
        Instant from = NOW.minus(Duration.ofDays(7));
        when(searchStatsRepository.totals(from, NOW)).thenReturn(new SearchStatsRepository.Totals(10, 4, 2));
        when(searchStatsRepository.topQueries(from, NOW, 5)).thenReturn(List.of());
        when(searchStatsRepository.zeroResultQueries(from, NOW, 5)).thenReturn(List.of());

        SearchStatsResponse stats = service.stats(7, 5);

        assertThat(stats.from()).isEqualTo(from);
        assertThat(stats.to()).isEqualTo(NOW);
        assertThat(stats.totalSearches()).isEqualTo(10);
        assertThat(stats.zeroResultRate()).isEqualTo(0.2);
    }

    @Test
    void statsRejectPeriodsAndLimitsOutOfRange() {
        assertThatThrownBy(() -> service.stats(0, 20)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.stats(366, 20)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.stats(30, 101)).isInstanceOf(BadRequestException.class);
    }
}
