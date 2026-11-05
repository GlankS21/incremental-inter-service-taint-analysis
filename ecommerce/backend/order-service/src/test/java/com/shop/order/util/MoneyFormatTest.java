package com.shop.order.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyFormatTest {

    @Test
    void writesRublesTheRussianWay() {
        assertThat(MoneyFormat.format(new BigDecimal("1234.5"), "RUB")).isEqualTo("1 234,50 ₽");
        assertThat(MoneyFormat.format(new BigDecimal("25140000"), "RUB")).isEqualTo("25 140 000,00 ₽");
        assertThat(MoneyFormat.format(new BigDecimal("0.999"), null)).isEqualTo("1,00 ₽");
    }

    @Test
    void keepsTheCodeOfOtherCurrencies() {
        assertThat(MoneyFormat.format(new BigDecimal("19.99"), "USD")).isEqualTo("19,99 USD");
    }

    @Test
    void writesADashWithoutAmount() {
        assertThat(MoneyFormat.format(null, "RUB")).isEqualTo("—");
    }
}
