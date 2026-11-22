package com.shop.auth;

import com.shop.auth.controller.AdminAccountController;
import com.shop.auth.controller.AuthController;
import com.shop.auth.service.impl.AccountAdminServiceImpl;
import com.shop.auth.service.impl.AuthServiceImpl;
import com.shop.auth.service.impl.PasswordResetServiceImpl;
import com.shop.auth.service.impl.TokenServiceImpl;
import com.shop.auth.util.PasswordPolicy;
import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every message key used by the code is defined in {@code messages_ru.properties}.
 */
class MessagesBundleTest {

    private final MessageSource messageSource = new CommonMessagesAutoConfiguration().messageSource();

    @Test
    void everyMessageKeyConstantIsTranslated() throws IllegalAccessException {
        List<String> keys = new ArrayList<>();
        for (Class<?> type : List.of(AuthController.class, AdminAccountController.class, AuthServiceImpl.class,
                TokenServiceImpl.class, AccountAdminServiceImpl.class, PasswordResetServiceImpl.class)) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    String value = (String) field.get(null);
                    if (value.startsWith("auth.")) {
                        keys.add(value);
                    }
                }
            }
        }

        assertThat(keys).hasSizeGreaterThanOrEqualTo(20);
        assertThat(keys).allSatisfy(key -> assertThat(translation(key)).as(key).isNotNull().isNotEqualTo(key));
    }

    @Test
    void passwordPolicyMessageIsAKeyOfTheBundle() {
        String key = PasswordPolicy.PATTERN_MESSAGE.substring(1, PasswordPolicy.PATTERN_MESSAGE.length() - 1);

        assertThat(PasswordPolicy.PATTERN_MESSAGE).startsWith("{").endsWith("}");
        assertThat(translation(key)).isEqualTo("Пароль должен содержать хотя бы одну букву и одну цифру");
    }

    @Test
    void passwordByteLimitMessageIsAKeyOfTheBundle() {
        String key = PasswordPolicy.MAX_BYTES_MESSAGE.substring(1, PasswordPolicy.MAX_BYTES_MESSAGE.length() - 1);

        assertThat(PasswordPolicy.MAX_BYTES_MESSAGE).startsWith("{").endsWith("}");
        assertThat(translation(key)).isEqualTo("Пароль слишком длинный");
    }

    @Test
    void textsAreRussian() {
        Stream.of("auth.error.invalid-credentials", "auth.registered", "auth.admin.roles-updated")
                .forEach(key -> assertThat(translation(key)).matches(".*[А-Яа-яЁё].*"));
    }

    private String translation(String key) {
        return messageSource.getMessage(key, null, null, CommonMessagesAutoConfiguration.DEFAULT_LOCALE);
    }
}
