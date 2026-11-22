package com.shop.product.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeyboardLayoutTest {

    @Test
    void retypesLatinKeysOnTheRussianLayout() {
        assertThat(KeyboardLayout.switchLayout("ntktajy")).isEqualTo("телефон");
        assertThat(KeyboardLayout.switchLayout("yfeiybrb")).isEqualTo("наушники");
        // Keys that are punctuation on the Latin layout are letters on the Russian one
        assertThat(KeyboardLayout.switchLayout("rkfdbfnehf")).isEqualTo("клавиатура");
        assertThat(KeyboardLayout.switchLayout("[jkjlbkmybr")).isEqualTo("холодильник");
    }

    @Test
    void retypesRussianKeysOnTheLatinLayout() {
        assertThat(KeyboardLayout.switchLayout("шзфв")).isEqualTo("ipad");
        assertThat(KeyboardLayout.switchLayout("ыфьыгтп 128")).isEqualTo("samsung 128");
    }

    @Test
    void leavesMixedOrScriptlessTextAlone() {
        assertThat(KeyboardLayout.switchLayout("iphone чехол")).isNull();
        assertThat(KeyboardLayout.switchLayout("12345")).isNull();
        assertThat(KeyboardLayout.switchLayout(null)).isNull();
    }
}
