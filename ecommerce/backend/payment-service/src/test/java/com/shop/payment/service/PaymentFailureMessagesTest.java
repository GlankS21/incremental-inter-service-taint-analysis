package com.shop.payment.service;

import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentFailureMessagesTest {

    private final PaymentFailureMessages messages =
            new PaymentFailureMessages(new CommonMessagesAutoConfiguration().messageSource());

    @Test
    void describesProviderAndServiceCodesInRussian() {
        assertThat(messages.describe("insufficient_funds")).isEqualTo("Недостаточно средств для оплаты");
        assertThat(messages.describe("3d_secure_failed")).isEqualTo("Не пройдена проверка 3-D Secure");
        assertThat(messages.describe("expired_on_confirmation")).isEqualTo("Истекло время на подтверждение платежа");
        assertThat(messages.describe(PaymentLedger.ORDER_CANCELLED)).isEqualTo("Заказ отменён");
        assertThat(messages.describe(PaymentLedger.UNSUPPORTED_CURRENCY)).isEqualTo("Валюта платежа не поддерживается");
    }

    @Test
    void unknownCodeGetsAGenericTextAndNoCodeNoText() {
        assertThat(messages.describe("brand_new_reason")).isEqualTo("Платёж не прошёл");
        assertThat(messages.describe(null)).isNull();
        assertThat(messages.describe(" ")).isNull();
    }
}
