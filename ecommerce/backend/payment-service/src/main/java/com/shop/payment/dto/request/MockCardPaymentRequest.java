package com.shop.payment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Card typed in the mock payment form ({@code PAYMENT_PROVIDER=mock} only; with YooKassa the card is
 * entered in the YooKassa widget and never reaches the shop).
 *
 * @param cardNumber       digits only, one of the YooKassa test cards
 * @param expiry           {@code MM/YY}
 * @param cvc              any 3 or 4 digits
 * @param threeDSecureCode code of the imitated 3-D Secure page, sent when the card asked for it
 */
public record MockCardPaymentRequest(
        @Schema(description = "Номер тестовой карты ЮKassa, только цифры", example = "5555555555554444")
        @NotBlank @Pattern(regexp = "^\\d{12,19}$", message = "{payment.validation.card-number}")
        String cardNumber,

        @Schema(description = "Срок действия в формате ММ/ГГ", example = "12/30")
        @NotBlank @Pattern(regexp = "^(0[1-9]|1[0-2])/\\d{2}$", message = "{payment.validation.expiry}")
        String expiry,

        @Schema(description = "Любые 3 или 4 цифры", example = "123")
        @NotBlank @Pattern(regexp = "^\\d{3,4}$", message = "{payment.validation.cvc}")
        String cvc,

        @Schema(description = "Код имитации 3-D Secure; передаётся, если карта его запросила", example = "1234")
        @Pattern(regexp = "^\\d{1,8}$", message = "{payment.validation.three-d-secure-code}")
        String threeDSecureCode
) {

    @Override
    public String toString() {
        // Never print the card, e.g. in a validation log
        return "MockCardPaymentRequest[card=****]";
    }
}
