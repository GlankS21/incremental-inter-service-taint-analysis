package com.shop.product.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalisation of search queries. A query is trimmed, lower-cased, "ё" becomes "е" and runs of white
 * space become one space; punctuation is kept (the keyboard layout conversion needs it). Its words
 * ("tokens") are the runs of letters and digits, which is also the only text ever placed inside a
 * {@code to_tsquery} expression.
 */
public final class SearchText {

    /** Longest accepted query, in characters. */
    public static final int MAX_QUERY_LENGTH = 100;
    /** Words beyond this number are ignored. */
    public static final int MAX_TOKENS = 8;

    private static final Pattern WHITE_SPACE = Pattern.compile("\\s+");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern DIGITS = Pattern.compile("\\p{N}+");

    private SearchText() {
    }

    /**
     * @return the normalised query, {@code null} when it has no letter or digit
     */
    public static String normalize(String query) {
        if (query == null) {
            return null;
        }
        String normalized = WHITE_SPACE.matcher(query.toLowerCase(Locale.ROOT).replace('ё', 'е'))
                .replaceAll(" ")
                .strip();
        return TOKEN.matcher(normalized).find() ? normalized : null;
    }

    /**
     * Words of a normalised query in their order, duplicates kept, at most {@link #MAX_TOKENS}.
     */
    public static List<String> tokens(String normalized) {
        List<String> tokens = new ArrayList<>();
        if (normalized == null) {
            return tokens;
        }
        Matcher matcher = TOKEN.matcher(normalized);
        while (matcher.find() && tokens.size() < MAX_TOKENS) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /**
     * The words of a normalised query joined by single spaces (punctuation dropped).
     */
    public static String canonical(String normalized) {
        return String.join(" ", tokens(normalized));
    }

    public static boolean isNumber(String token) {
        return DIGITS.matcher(token).matches();
    }

    /**
     * Escapes the LIKE wildcards of a value used in a pattern with {@code escape '\'}.
     */
    public static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
