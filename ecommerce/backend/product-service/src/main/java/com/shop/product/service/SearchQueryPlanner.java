package com.shop.product.service;

import com.shop.product.repository.ProductSearchRepository;
import com.shop.product.repository.SearchDictionaryRepository;
import com.shop.product.repository.SearchPlan;
import com.shop.product.util.KeyboardLayout;
import com.shop.product.util.SearchText;
import com.shop.product.util.Transliteration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a normalised query into {@link SearchPlan}s and proposes corrections of queries that match
 * nothing:
 * <ol>
 *     <li>synonyms: every word may be replaced by a term of its synonym group, the whole query by a
 *     multi-word term;</li>
 *     <li>keyboard layout: "ntktajy" is "телефон" typed on the Latin layout;</li>
 *     <li>spelling: an unknown word is replaced by the closest catalogue word, also across alphabets
 *     ("самсунк" → "samsung");</li>
 *     <li>trigram needles: the query and its transliterations, for the typo-tolerant fallback.</li>
 * </ol>
 * Must run inside the transaction of the search, after the similarity thresholds were set.
 */
@Component
@RequiredArgsConstructor
public class SearchQueryPlanner {

    /** Shorter words are never corrected nor completed by prefix. */
    static final int MIN_CORRECTABLE_LENGTH = 3;
    public static final int MIN_PREFIX_LENGTH = 2;
    static final int MIN_NEEDLE_LENGTH = 3;

    private final SearchDictionaryRepository dictionaryRepository;
    private final ProductSearchRepository productSearchRepository;

    /**
     * @param prefixLast the last word may be incomplete (type-ahead, or a query submitted mid-word): words
     *                   starting with it match too
     */
    public SearchPlan plan(String normalizedQuery, boolean prefixLast) {
        List<String> tokens = SearchText.tokens(normalizedQuery);
        if (tokens.isEmpty()) {
            return SearchPlan.EMPTY;
        }
        String whole = String.join(" ", tokens);
        Set<String> candidates = new LinkedHashSet<>(tokens);
        if (tokens.size() > 1) {
            candidates.add(whole);
        }
        Map<String, List<String>> synonyms = dictionaryRepository.synonyms(candidates);

        List<SearchPlan.Slot> slots = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            List<String> terms = new ArrayList<>();
            terms.add(token);
            terms.addAll(synonyms.getOrDefault(token, List.of()));
            boolean last = i == tokens.size() - 1;
            String prefix = prefixLast && last && token.length() >= MIN_PREFIX_LENGTH ? token : null;
            slots.add(new SearchPlan.Slot(terms, prefix));
        }
        List<String> wholeAlternatives = tokens.size() > 1 ? synonyms.getOrDefault(whole, List.of()) : List.of();
        return new SearchPlan(slots, wholeAlternatives, List.of());
    }

    /**
     * Alternative queries to try, in order, when the query matches nothing: the other keyboard layout,
     * the spelling correction, the spelling correction of the other layout. Never contains the query.
     */
    public List<String> corrections(String normalizedQuery) {
        Set<String> corrections = new LinkedHashSet<>();
        String switched = SearchText.normalize(KeyboardLayout.switchLayout(normalizedQuery));
        if (switched != null) {
            corrections.add(SearchText.canonical(switched));
        }
        corrections.add(correctSpelling(normalizedQuery));
        if (switched != null) {
            corrections.add(correctSpelling(switched));
        }
        String canonical = SearchText.canonical(normalizedQuery);
        corrections.removeIf(correction -> correction == null || correction.isEmpty() || correction.equals(canonical));
        return List.copyOf(corrections);
    }

    /**
     * Replaces each unknown word by the closest catalogue word; a word is known when it is a catalogue
     * word, has synonyms or matches a product by itself (after stemming).
     *
     * @return the corrected query, {@code null} when no word changed
     */
    public String correctSpelling(String normalizedQuery) {
        List<String> tokens = SearchText.tokens(normalizedQuery);
        if (tokens.isEmpty()) {
            return null;
        }
        Map<String, List<String>> synonyms = dictionaryRepository.synonyms(tokens);
        List<String> corrected = new ArrayList<>(tokens.size());
        boolean changed = false;
        for (String token : tokens) {
            String replacement = token;
            if (isCorrectable(token) && !synonyms.containsKey(token) && !dictionaryRepository.isKnownWord(token)
                    && !productSearchRepository.anyActiveMatch(plan(token, false))) {
                replacement = dictionaryRepository.closestWord(spellings(token)).orElse(token);
            }
            changed |= !replacement.equals(token);
            corrected.add(replacement);
        }
        return changed ? String.join(" ", corrected) : null;
    }

    /**
     * The query and its spellings in the other alphabet, for trigram word similarity.
     */
    public List<String> trigramNeedles(String normalizedQuery) {
        String canonical = SearchText.canonical(normalizedQuery);
        return spellings(canonical).stream()
                .filter(needle -> needle.length() >= MIN_NEEDLE_LENGTH)
                .toList();
    }

    private static boolean isCorrectable(String token) {
        return token.length() >= MIN_CORRECTABLE_LENGTH && !SearchText.isNumber(token);
    }

    private static List<String> spellings(String text) {
        Set<String> spellings = new LinkedHashSet<>();
        spellings.add(text);
        spellings.add(Transliteration.toLatin(text));
        spellings.add(Transliteration.toCyrillic(text));
        return spellings.stream().filter(Objects::nonNull).toList();
    }
}
