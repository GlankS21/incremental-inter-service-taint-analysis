package com.shop.product.repository;

import com.shop.product.dto.response.SearchStatsResponse.QueryStat;
import com.shop.product.dto.response.SearchStatsResponse.ZeroResultQueryStat;
import com.shop.product.util.SearchText;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Aggregates of the search log ({@code search_queries}): popular queries, completions and statistics.
 */
@Repository
@RequiredArgsConstructor
public class SearchStatsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Most frequent queries that found something since the given instant; a query found through a correction
     * (keyboard layout, spelling) counts as its correction, so that misspellings are never suggested.
     */
    public List<String> popularQueries(Instant since, int limit) {
        return jdbc.queryForList("""
                select coalesce(corrected_query, query) as query
                from search_queries
                where searched_at >= :since and results_count > 0
                group by coalesce(corrected_query, query)
                order by count(*) desc, max(searched_at) desc
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("since", Timestamp.from(since))
                .addValue("limit", limit), String.class);
    }

    /**
     * Longer past queries with results, found as typed (not through a correction), that start with the prefix,
     * most frequent first.
     */
    public List<String> queriesStartingWith(String prefix, Instant since, int limit) {
        return jdbc.queryForList("""
                select query
                from search_queries
                where query like :pattern escape '\\' and query <> :prefix
                  and searched_at >= :since and results_count > 0 and corrected_query is null
                group by query
                order by count(*) desc, query
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("pattern", SearchText.escapeLike(prefix) + "%")
                .addValue("prefix", prefix)
                .addValue("since", Timestamp.from(since))
                .addValue("limit", limit), String.class);
    }

    public Totals totals(Instant from, Instant to) {
        return jdbc.queryForObject("""
                select count(*) as searches,
                       count(distinct query) as unique_queries,
                       count(*) filter (where results_count = 0) as zero_result_searches
                from search_queries
                where searched_at >= :from and searched_at < :to
                """, period(from, to), (rs, row) -> new Totals(rs.getLong("searches"), rs.getLong("unique_queries"),
                rs.getLong("zero_result_searches")));
    }

    public List<QueryStat> topQueries(Instant from, Instant to, int limit) {
        return jdbc.query("""
                select query, count(*) as searches, avg(results_count) as average_results,
                       max(searched_at) as last_searched_at
                from search_queries
                where searched_at >= :from and searched_at < :to
                group by query
                order by searches desc, last_searched_at desc
                limit :limit
                """, period(from, to).addValue("limit", limit), (rs, row) -> new QueryStat(rs.getString("query"),
                rs.getLong("searches"), rs.getDouble("average_results"),
                rs.getTimestamp("last_searched_at").toInstant()));
    }

    public List<ZeroResultQueryStat> zeroResultQueries(Instant from, Instant to, int limit) {
        return jdbc.query("""
                select query, count(*) as searches, max(searched_at) as last_searched_at
                from search_queries
                where searched_at >= :from and searched_at < :to and results_count = 0
                group by query
                order by searches desc, last_searched_at desc
                limit :limit
                """, period(from, to).addValue("limit", limit), (rs, row) -> new ZeroResultQueryStat(
                rs.getString("query"), rs.getLong("searches"), rs.getTimestamp("last_searched_at").toInstant()));
    }

    private static MapSqlParameterSource period(Instant from, Instant to) {
        return new MapSqlParameterSource()
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to));
    }

    public record Totals(long searches, long uniqueQueries, long zeroResultSearches) {
    }
}
