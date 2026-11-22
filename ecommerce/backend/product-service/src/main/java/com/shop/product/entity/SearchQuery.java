package com.shop.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One catalogue search by a customer (first page only), kept for popular queries, completions and the
 * statistics of searches without results. No user is recorded.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "search_queries")
public class SearchQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalised query (trimmed, lower case, single spaces). */
    @Column(nullable = false, length = 100, updatable = false)
    private String query;

    /** Correction the results were found for, {@code null} when the query was used as typed. */
    @Column(name = "corrected_query", length = 100, updatable = false)
    private String correctedQuery;

    @Column(name = "results_count", nullable = false, updatable = false)
    private int resultsCount;

    @Column(name = "searched_at", nullable = false, updatable = false)
    private Instant searchedAt;
}
