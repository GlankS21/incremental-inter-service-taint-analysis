package com.shop.notification.template;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyFormatterTest {

    @Test
    void formatsRoublesTheRussianWay() {
        assertThat(MoneyFormatter.format(new BigDecimal("1234.5"), "RUB")).isEqualTo("1 234,50 ₽");
        assertThat(MoneyFormatter.format(new BigDecimal("25140000"), "RUB")).isEqualTo("25 140 000,00 ₽");
        assertThat(MoneyFormatter.format(new BigDecimal("99.995"), null)).isEqualTo("100,00 ₽");
        assertThat(MoneyFormatter.format(new BigDecimal("0"), "RUB")).isEqualTo("0,00 ₽");
    }

    @Test
    void keepsTheCodeOfOtherCurrencies() {
        assertThat(MoneyFormatter.format(new BigDecimal("1234.5"), "USD")).isEqualTo("1 234,50 USD");
    }

    @Test
    void writesADashWithoutAmount() {
        assertThat(MoneyFormatter.format(null, "RUB")).isEqualTo("—");
    }
}
