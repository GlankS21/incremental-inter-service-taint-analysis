package com.shop.common.validation;

import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation messages are Russian, as every service configures its validator (Spring Boot's
 * interpolator backed by the application {@code MessageSource}, request locale from
 * {@code CommonLocaleAutoConfiguration}).
 */
class RussianValidationMessagesTest {

    record Form(@NotBlank String name,
                @Pattern(regexp = "^\\+7\\d{10}$", message = "{validation.phone}") String phone) {
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void builtInAndSharedMessagesAreRussian() {
        LocaleContextHolder.setLocale(CommonMessagesAutoConfiguration.DEFAULT_LOCALE);
        Locale.setDefault(Locale.US);

        Set<ConstraintViolation<Form>> violations = validator().validate(new Form(" ", "0912345678"));

        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder("не должно быть пустым", "Укажите номер телефона в формате +7XXXXXXXXXX");
    }

    private static LocalValidatorFactoryBean validator() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean("messageSource", org.springframework.context.MessageSource.class,
                () -> new CommonMessagesAutoConfiguration().messageSource());
        context.refresh();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setMessageInterpolator(new MessageInterpolatorFactory(context).getObject());
        validator.afterPropertiesSet();
        return validator;
    }
}
