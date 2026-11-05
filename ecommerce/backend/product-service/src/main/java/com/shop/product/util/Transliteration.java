package com.shop.product.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phonetic spelling of a word in the other alphabet, used to compare words across alphabets by
 * trigram similarity: "самсунк" → "samsunk" (close to "samsung"), "ayfon" → "айфон". Expects
 * lower-case text; other characters are kept.
 */
public final class Transliteration {

    /** Cyrillic а (U+0430) to я (U+044F), in alphabetical order. */
    private static final String[] CYRILLIC_TO_LATIN = {
            "a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p",
            "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"
    };
    private static final char CYRILLIC_FIRST = 'а';
    private static final char CYRILLIC_LAST = 'я';

    /** Latin letter groups, longest first so that "shch" wins over "sh" and "ch". */
    private static final Map<String, String> LATIN_TO_CYRILLIC = new LinkedHashMap<>();

    static {
        String[][] groups = {
                {"shch", "щ"}, {"sch", "щ"}, {"zh", "ж"}, {"kh", "х"}, {"ts", "ц"}, {"ch", "ч"}, {"sh", "ш"},
                {"yu", "ю"}, {"ya", "я"}, {"yo", "е"}, {"ph", "ф"}, {"ck", "к"}, {"ee", "и"}, {"oo", "у"},
                {"a", "а"}, {"b", "б"}, {"c", "к"}, {"d", "д"}, {"e", "е"}, {"f", "ф"}, {"g", "г"}, {"h", "х"},
                {"i", "и"}, {"j", "дж"}, {"k", "к"}, {"l", "л"}, {"m", "м"}, {"n", "н"}, {"o", "о"}, {"p", "п"},
                {"q", "к"}, {"r", "р"}, {"s", "с"}, {"t", "т"}, {"u", "у"}, {"v", "в"}, {"w", "в"}, {"x", "кс"},
                {"y", "и"}, {"z", "з"}
        };
        for (String[] group : groups) {
            LATIN_TO_CYRILLIC.put(group[0], group[1]);
        }
    }

    private Transliteration() {
    }

    public static String toLatin(String text) {
        StringBuilder result = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= CYRILLIC_FIRST && c <= CYRILLIC_LAST) {
                result.append(CYRILLIC_TO_LATIN[c - CYRILLIC_FIRST]);
            } else if (c == 'ё') {
                result.append('e');
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    public static String toCyrillic(String text) {
        StringBuilder result = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            String replacement = null;
            int length = 1;
            for (Map.Entry<String, String> group : LATIN_TO_CYRILLIC.entrySet()) {
                if (text.startsWith(group.getKey(), i)) {
                    replacement = group.getValue();
                    length = group.getKey().length();
                    break;
                }
            }
            result.append(replacement != null ? replacement : String.valueOf(text.charAt(i)));
            i += length;
        }
        return result.toString();
    }
}
