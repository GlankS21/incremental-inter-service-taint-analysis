package com.shop.product.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransliterationTest {

    @Test
    void spellsCyrillicInLatin() {
        assertThat(Transliteration.toLatin("самсунг")).isEqualTo("samsung");
        assertThat(Transliteration.toLatin("самсунк")).isEqualTo("samsunk");
        assertThat(Transliteration.toLatin("iphone 15")).isEqualTo("iphone 15");
    }

    @Test
    void spellsLatinInCyrillic() {
        assertThat(Transliteration.toCyrillic("samsung")).isEqualTo("самсунг");
        assertThat(Transliteration.toCyrillic("lenovo")).isEqualTo("леново");
        assertThat(Transliteration.toCyrillic("телефон")).isEqualTo("телефон");
    }
}
