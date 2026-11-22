package com.shop.product.repository;

import com.shop.product.util.SearchText;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Words of the catalogue ({@code search_words}, a materialized view of the words of active product names,
 * brands and category names) and the synonym groups, as used while planning a search.
 */
@Repository
@RequiredArgsConstructor
public class SearchDictionaryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public boolean isKnownWord(String word) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from search_words where word = :word)",
                new MapSqlParameterSource("word", word), Boolean.class));
    }

    /**
     * Dictionary word most similar to any of the candidates (pg_trgm {@code %}, so above the similarity
     * threshold of the transaction), excluding the candidates themselves; frequent words win ties.
     */
    public Optional<String> closestWord(Collection<String> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<String> words = jdbc.queryForList("""
                select w.word
                from search_words w
                         join unnest(array[:candidates]::text[]) as c(candidate) on w.word % c.candidate
                where w.word <> all (array[:candidates]::text[])
                order by similarity(w.word, c.candidate) desc, w.frequency desc, w.word
                limit 1
                """, new MapSqlParameterSource("candidates", List.copyOf(candidates)), String.class);
        return words.stream().findFirst();
    }

    /**
     * Longer dictionary words starting with the prefix, most frequent first.
     */
    public List<String> wordsStartingWith(String prefix, int limit) {
        return jdbc.queryForList("""
                select word
                from search_words
                where word like :pattern escape '\\' and word <> :prefix
                order by frequency desc, length(word), word
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("pattern", SearchText.escapeLike(prefix) + "%")
                .addValue("prefix", prefix)
                .addValue("limit", limit), String.class);
    }

    /**
     * Synonyms of each candidate that belongs to a group, compared as typed or after Russian stemming
     * ("телефоны" finds the group of "телефон"). Candidates without synonyms are absent from the map.
     */
    public Map<String, List<String>> synonyms(Collection<String> candidates) {
        Map<String, List<String>> synonyms = new LinkedHashMap<>();
        if (candidates.isEmpty()) {
            return synonyms;
        }
        jdbc.query("""
                select distinct c.candidate, other.term
                from unnest(array[:candidates]::text[]) as c(candidate)
                         join search_synonym_terms t
                              on t.term = c.candidate
                                  or (numnode(plainto_tsquery('shop_russian', c.candidate)) > 0
                                      and plainto_tsquery('shop_russian', t.term)
                                          = plainto_tsquery('shop_russian', c.candidate))
                         join search_synonym_terms other
                              on other.synonym_id = t.synonym_id and other.term <> t.term
                where other.term <> c.candidate
                order by c.candidate, other.term
                """, new MapSqlParameterSource("candidates", List.copyOf(candidates)), rs -> {
            synonyms.computeIfAbsent(rs.getString("candidate"), key -> new ArrayList<>())
                    .add(rs.getString("term"));
        });
        return synonyms;
    }

    /**
     * Rebuilds the dictionary without blocking searches (the view has a unique index).
     */
    public void refresh() {
        jdbc.getJdbcTemplate().execute("refresh materialized view concurrently search_words");
    }
}
