package com.shop.product.exception;

import com.shop.common.dto.ErrorResponse;
import com.shop.product.config.CatalogProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void tooLargeUploadGivesCleanBadRequest() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasenames("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setDefaultLocale(Locale.forLanguageTag("ru-RU"));
        messageSource.setFallbackToSystemLocale(false);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(new CatalogProperties("RUB", null, null));
        handler.setMessageSource(messageSource);

        ResponseEntity<ErrorResponse> response = handler.handleMaxUploadSize(
                new MaxUploadSizeExceededException(6L * 1024 * 1024),
                new MockHttpServletRequest("POST", "/api/products/images"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().getMessage()).isEqualTo("Размер изображения не должен превышать 5 МБ");
    }
}
