package com.shop.common.util;

/**
 * Text helpers shared by the services.
 */
public final class TextUtils {

    private TextUtils() {
    }

    /**
     * Cuts {@code text} to at most {@code maxLength} chars without splitting a surrogate pair: half of an
     * emoji would be stored by PostgreSQL as a replacement character.
     *
     * @return the text itself when it is short enough, {@code null} for {@code null}
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return text.substring(0, end);
    }
}
