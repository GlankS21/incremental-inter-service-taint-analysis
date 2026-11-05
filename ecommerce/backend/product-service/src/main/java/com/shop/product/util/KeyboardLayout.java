package com.shop.product.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Retypes text entered with the wrong keyboard layout between the US QWERTY and the Russian ЙЦУКЕН
 * layouts, key by key: "ntktajy" → "телефон", "ыфьыгтп" → "samsung". Expects lower-case text (queries
 * are normalised first); characters without a counterpart are kept.
 */
public final class KeyboardLayout {

    private static final String LATIN_KEYS = "qwertyuiop[]asdfghjkl;'zxcvbnm,.";
    private static final String CYRILLIC_KEYS = "йцукенгшщзхъфывапролджэячсмитьбю";

    private static final Map<Character, Character> TO_CYRILLIC = new HashMap<>();
    private static final Map<Character, Character> TO_LATIN = new HashMap<>();

    static {
        for (int i = 0; i < LATIN_KEYS.length(); i++) {
            TO_CYRILLIC.put(LATIN_KEYS.charAt(i), CYRILLIC_KEYS.charAt(i));
            TO_LATIN.put(CYRILLIC_KEYS.charAt(i), LATIN_KEYS.charAt(i));
        }
    }

    private KeyboardLayout() {
    }

    /**
     * @return the text typed on the other layout, or {@code null} when the text mixes Latin and Cyrillic
     * letters, has none of them, or would not change
     */
    public static String switchLayout(String text) {
        if (text == null) {
            return null;
        }
        boolean latin = false;
        boolean cyrillic = false;
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            latin |= script == Character.UnicodeScript.LATIN;
            cyrillic |= script == Character.UnicodeScript.CYRILLIC;
        }
        if (latin == cyrillic) {
            return null;
        }
        Map<Character, Character> table = latin ? TO_CYRILLIC : TO_LATIN;
        StringBuilder converted = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            converted.append(table.getOrDefault(c, c));
        }
        String result = converted.toString();
        return result.equals(text) ? null : result;
    }
}
