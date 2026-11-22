package com.shop.user.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RussianPluralTest {

    @ParameterizedTest
    @CsvSource({
            "1, 0", "21, 0", "101, 0", "1001, 0",
            "2, 1", "3, 1", "4, 1", "22, 1", "34, 1", "102, 1",
            "0, 2", "5, 2", "10, 2", "11, 2", "12, 2", "13, 2", "14, 2", "19, 2", "20, 2", "25, 2",
            "111, 2", "112, 2", "114, 2", "-1, 0", "-12, 2"
    })
    void categoryFollowsTheRussianRules(long count, int expected) {
        assertThat(RussianPlural.category(count)).isEqualTo(expected);
    }
}
