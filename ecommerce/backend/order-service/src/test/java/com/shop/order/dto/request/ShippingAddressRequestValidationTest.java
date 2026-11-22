package com.shop.order.dto.request;

import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address is validated with the Russian messages of the application, as in the running service.
 */
class ShippingAddressRequestValidationTest {

    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(CommonMessagesAutoConfiguration.DEFAULT_LOCALE);
        validator = new LocalValidatorFactoryBean();
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean("messageSource", MessageSource.class,
                () -> new CommonMessagesAutoConfiguration().messageSource());
        context.refresh();
        // Spring Boot's interpolator, backed by the application MessageSource, as in the running service
        validator.setMessageInterpolator(new MessageInterpolatorFactory(context).getObject());
        validator.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        validator.close();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void acceptsARussianAddress() {
        assertThat(validator.validate(address("+79161234567", "RU"))).isEmpty();
    }

    @Test
    void refusesForeignPhoneNumbersAndBadCountryCodesInRussian() {
        Map<String, String> errors = violations(address("+84901234567", "ru"));

        assertThat(errors).containsOnly(
                Map.entry("phoneNumber", "Укажите номер телефона в формате +7XXXXXXXXXX"),
                Map.entry("countryCode", "Укажите двухбуквенный код страны ISO 3166-1, например RU"));
    }

    @Test
    void refusesNumbersWithoutThePlusOrWithTheWrongLength() {
        assertThat(violations(address("89161234567", "RU"))).containsKey("phoneNumber");
        assertThat(violations(address("+7916123456", "RU"))).containsKey("phoneNumber");
        assertThat(violations(address("+791612345678", "RU"))).containsKey("phoneNumber");
    }

    @Test
    void builtInMessagesAreRussianToo() {
        ShippingAddressRequest blank = new ShippingAddressRequest(" ", "+79161234567", "ул. Тверская, д. 7", null,
                null, null, "Москва", "125009", "RU");

        assertThat(violations(blank)).containsEntry("recipientName", "не должно быть пустым");
    }

    private Map<String, String> violations(ShippingAddressRequest request) {
        return validator.validate(request).stream().collect(Collectors.toMap(
                violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }

    private static ShippingAddressRequest address(String phone, String country) {
        return new ShippingAddressRequest("Иван Петров", phone, "ул. Тверская, д. 7", "кв. 12", "Тверской", "ЦАО",
                "Москва", "125009", country);
    }
}
