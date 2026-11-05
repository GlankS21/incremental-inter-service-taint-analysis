package com.shop.notification.template;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RussianPluralTest {

    @ParameterizedTest
    @CsvSource({
            "0, 0 товаров",
            "1, 1 товар",
            "2, 2 товара",
            "4, 4 товара",
            "5, 5 товаров",
            "11, 11 товаров",
            "12, 12 товаров",
            "14, 14 товаров",
            "21, 21 товар",
            "22, 22 товара",
            "25, 25 товаров",
            "101, 101 товар",
            "111, 111 товаров",
            "112, 112 товаров",
            "1001, 1001 товар"
    })
    void agreesTheNounWithTheCount(long count, String expected) {
        assertThat(RussianPlural.withCount(count, "товар", "товара", "товаров")).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"1, штука", "3, штуки", "13, штук", "-2, штуки"})
    void selectsTheForm(long count, String expected) {
        assertThat(RussianPlural.select(count, "штука", "штуки", "штук")).isEqualTo(expected);
    }
}
