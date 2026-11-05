package com.shop.cart;

import com.shop.cart.util.RussianPlural;
import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.MessageSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every message key used by cart-service is defined in {@code messages_ru.properties}, and the
 * messages with a count agree with it.
 */
class MessagesBundleTest {

    private final MessageSource messageSource = new CommonMessagesAutoConfiguration().messageSource();

    @Test
    void everyKeyIsTranslated() {
        List<String> keys = List.of("cart.item-added", "cart.updated", "cart.item-removed", "cart.cleared",
                "cart.error.product-not-available", "cart.error.not-in-cart", "cart.error.catalogue-unavailable",
                "cart.error.catalogue-no-data", "cart.error.stock-unavailable");

        assertThat(keys).allSatisfy(key -> assertThat(messageSource.getMessage(key, null, null,
                CommonMessagesAutoConfiguration.DEFAULT_LOCALE)).as(key)
                .isNotNull()
                .matches(".*[А-Яа-яЁё].*"));
    }

    @ParameterizedTest
    @CsvSource({
            "1, Товара «Кабель USB-C» в наличии только 1 штука",
            "3, Товара «Кабель USB-C» в наличии только 3 штуки",
            "5, Товара «Кабель USB-C» в наличии только 5 штук",
            "12, Товара «Кабель USB-C» в наличии только 12 штук",
            "22, Товара «Кабель USB-C» в наличии только 22 штуки"
    })
    void insufficientStockSaysHowManyAreAvailable(int units, String expected) {
        assertThat(message("cart.error.insufficient-stock", "Кабель USB-C", units, RussianPlural.category(units)))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "1, Одного товара можно положить в корзину не больше 1 штуки",
            "4, Одного товара можно положить в корзину не больше 4 штук",
            "10, Одного товара можно положить в корзину не больше 10 штук"
    })
    void quantityLimitAgreesWithTheCount(int limit, String expected) {
        assertThat(message("cart.error.quantity-limit", limit, RussianPlural.category(limit))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "1, В корзине может быть не больше 1 разного товара",
            "2, В корзине может быть не больше 2 разных товаров",
            "50, В корзине может быть не больше 50 разных товаров"
    })
    void productLimitAgreesWithTheCount(int limit, String expected) {
        assertThat(message("cart.error.too-many-products", limit, RussianPlural.category(limit)))
                .isEqualTo(expected);
    }

    @Test
    void outOfStockNamesTheProduct() {
        assertThat(message("cart.error.out-of-stock", "USB-концентратор"))
                .isEqualTo("Товара «USB-концентратор» нет в наличии");
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, CommonMessagesAutoConfiguration.DEFAULT_LOCALE);
    }
}
