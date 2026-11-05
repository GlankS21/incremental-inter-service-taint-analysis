package com.shop.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextUtilsTest {

    private static final String EMOJI = new String(Character.toChars(0x1F600));

    @Test
    void keepsShortTextAndNull() {
        assertThat(TextUtils.truncate(null, 5)).isNull();
        assertThat(TextUtils.truncate("Привет", 6)).isEqualTo("Привет");
    }

    @Test
    void cutsAtTheLimit() {
        assertThat(TextUtils.truncate("Привет, мир", 6)).isEqualTo("Привет");
    }

    @Test
    void neverSplitsASurrogatePair() {
        // The emoji takes two chars, positions 3 and 4: a cut after 4 chars would keep only its first half
        String truncated = TextUtils.truncate("abc" + EMOJI + "def", 4);

        assertThat(truncated).isEqualTo("abc");
        assertThat(TextUtils.truncate("abc" + EMOJI + "def", 5)).isEqualTo("abc" + EMOJI);
    }
}
