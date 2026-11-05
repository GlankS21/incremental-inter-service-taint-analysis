package com.shop.common.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class CommonLocaleAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonLocaleAutoConfiguration.class));

    @Test
    void requestsAreAlwaysHandledInRussian() {
        runner.run(context -> {
            LocaleResolver resolver = context.getBean(DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME, LocaleResolver.class);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Accept-Language", "en-US,en;q=0.9");
            request.addPreferredLocale(Locale.US);

            assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.forLanguageTag("ru-RU"));
        });
    }

    @Test
    void aServiceMayDeclareItsOwnResolver() {
        LocaleResolver own = new org.springframework.web.servlet.i18n.FixedLocaleResolver(Locale.GERMANY);
        runner.withBean(DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME, LocaleResolver.class, () -> own)
                .run(context -> assertThat(context.getBean(DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME))
                        .isSameAs(own));
    }

    @Test
    void notAppliedOutsideServletApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonLocaleAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME));
    }
}
