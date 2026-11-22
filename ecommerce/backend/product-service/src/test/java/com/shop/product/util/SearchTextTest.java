package com.shop.product.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTextTest {

    @Test
    void normalizesCaseWhiteSpaceAndYo() {
        assertThat(SearchText.normalize("  Чёрный   iPhone\t15 ")).isEqualTo("черный iphone 15");
        assertThat(SearchText.normalize("USB-C")).isEqualTo("usb-c");
    }

    @Test
    void queriesWithoutLettersOrDigitsAreEmpty() {
        assertThat(SearchText.normalize(null)).isNull();
        assertThat(SearchText.normalize("   ")).isNull();
        assertThat(SearchText.normalize("%_*'\"--")).isNull();
    }

    @Test
    void splitsIntoWordsDroppingPunctuation() {
        assertThat(SearchText.tokens("кабель usb-c, 2 м")).containsExactly("кабель", "usb", "c", "2", "м");
        assertThat(SearchText.canonical("'; drop table products; --")).isEqualTo("drop table products");
        assertThat(SearchText.tokens(null)).isEmpty();
    }

    @Test
    void keepsAtMostEightWords() {
        assertThat(SearchText.tokens("a b c d e f g h i j")).hasSize(SearchText.MAX_TOKENS);
    }

    @Test
    void recognizesNumbers() {
        assertThat(SearchText.isNumber("128")).isTrue();
        assertThat(SearchText.isNumber("s24")).isFalse();
    }

    @Test
    void escapesLikeWildcards() {
        assertThat(SearchText.escapeLike("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
    }
}
