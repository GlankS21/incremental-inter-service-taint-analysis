package com.shop.product.repository;

import java.util.List;

/**
 * Text part of a catalogue search, built by {@code SearchQueryPlanner} and turned into SQL by
 * {@link ProductSearchRepository}. Every string here is bound as a parameter; only the {@code prefix} of a
 * slot is placed in a {@code to_tsquery} expression, which is why it must be a single run of letters and
 * digits.
 *
 * @param slots                  the words of the query, all required (AND)
 * @param wholeQueryAlternatives synonyms of the whole query (a multi-word term), any of which also matches
 * @param trigramNeedles         spellings compared by trigram word similarity with product names and
 *                               brands (typo tolerance); empty for a full-text only search
 */
public record SearchPlan(List<Slot> slots, List<String> wholeQueryAlternatives, List<String> trigramNeedles) {

    public static final SearchPlan EMPTY = new SearchPlan(List.of(), List.of(), List.of());

    public SearchPlan {
        slots = List.copyOf(slots);
        wholeQueryAlternatives = List.copyOf(wholeQueryAlternatives);
        trigramNeedles = List.copyOf(trigramNeedles);
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    public boolean usesTrigrams() {
        return !trigramNeedles.isEmpty();
    }

    public SearchPlan withTrigramNeedles(List<String> needles) {
        return new SearchPlan(slots, wholeQueryAlternatives, needles);
    }

    /**
     * One word of the query: any of its terms matches (the word itself and its synonyms, each stemmed),
     * or, for the word being typed, any word starting with {@code prefix}.
     *
     * @param prefix the typed word when it may be incomplete, {@code null} otherwise
     */
    public record Slot(List<String> terms, String prefix) {

        public Slot {
            terms = List.copyOf(terms);
        }
    }
}
