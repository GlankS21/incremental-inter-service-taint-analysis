package com.shop.user;

import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import com.shop.user.util.RussianPlural;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.MessageSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every message key used by user-service is defined in {@code messages_ru.properties}.
 */
class MessagesBundleTest {

    private final MessageSource messageSource = new CommonMessagesAutoConfiguration().messageSource();

    @Test
    void everyKeyIsTranslated() {
        List<String> keys = List.of("profile.updated", "profile.error.not-found", "address.created",
                "address.updated", "address.deleted", "address.default-updated", "address.error.not-found",
                "validation.phone", "validation.country-code");

        assertThat(keys).allSatisfy(key -> assertThat(translation(key)).as(key)
                .isNotNull()
                .matches(".*[А-Яа-яЁё].*"));
    }

    @ParameterizedTest
    @CsvSource({
            "1, В адресной книге можно сохранить не больше 1 адреса",
            "3, В адресной книге можно сохранить не больше 3 адресов",
            "10, В адресной книге можно сохранить не больше 10 адресов",
            "21, В адресной книге можно сохранить не больше 21 адреса"
    })
    void addressLimitAgreesWithTheCount(int limit, String expected) {
        String message = messageSource.getMessage("address.error.limit-reached",
                new Object[]{limit, RussianPlural.category(limit)}, CommonMessagesAutoConfiguration.DEFAULT_LOCALE);

        assertThat(message).isEqualTo(expected);
    }

    private String translation(String key) {
        return messageSource.getMessage(key, null, null, CommonMessagesAutoConfiguration.DEFAULT_LOCALE);
    }
}
