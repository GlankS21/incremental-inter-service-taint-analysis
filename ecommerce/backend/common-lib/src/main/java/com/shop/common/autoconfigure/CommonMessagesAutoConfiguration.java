package com.shop.common.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * Localized texts of every service: the service's own {@code messages_ru.properties} and the shared
 * {@code common-messages_ru.properties} of common-lib. Russian is the default and only language;
 * the system locale of the host is never used.
 */
@AutoConfiguration(before = MessageSourceAutoConfiguration.class)
public class CommonMessagesAutoConfiguration {

    public static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("ru-RU");

    static final String SERVICE_BASENAME = "messages";
    static final String COMMON_BASENAME = "common-messages";

    @Bean(name = AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME)
    @ConditionalOnMissingBean(name = AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME)
    public MessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        // A missing bundle would be looked up, and logged as missing, on every message
        messageSource.setBasenames(Stream.of(SERVICE_BASENAME, COMMON_BASENAME)
                .filter(CommonMessagesAutoConfiguration::bundleExists)
                .toArray(String[]::new));
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setDefaultLocale(DEFAULT_LOCALE);
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }

    private static boolean bundleExists(String basename) {
        return new ClassPathResource(basename + "_ru.properties").exists()
                || new ClassPathResource(basename + ".properties").exists();
    }
}
