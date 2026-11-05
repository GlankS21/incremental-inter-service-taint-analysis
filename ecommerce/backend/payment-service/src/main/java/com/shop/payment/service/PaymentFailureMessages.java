package com.shop.payment.service;

import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Customer-facing text of a failure reason code (a {@code cancellation_details.reason} of YooKassa such
 * as {@code insufficient_funds}, or a code of this service such as {@code order_cancelled}). The codes
 * stay the stored and returned identifiers; the text is resolved from {@code messages_ru.properties}
 * ({@code payment.failure.<code>}), with a generic text for codes without one.
 */
@Component
@RequiredArgsConstructor
public class PaymentFailureMessages {

    static final String KEY_PREFIX = "payment.failure.";
    static final String UNKNOWN_KEY = KEY_PREFIX + "unknown";

    private final MessageSource messageSource;

    /**
     * @return {@code null} without a reason
     */
    public String describe(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return null;
        }
        String generic = messageSource.getMessage(UNKNOWN_KEY, null, CommonMessagesAutoConfiguration.DEFAULT_LOCALE);
        return messageSource.getMessage(KEY_PREFIX + reasonCode, null, generic,
                CommonMessagesAutoConfiguration.DEFAULT_LOCALE);
    }
}
