package com.shop.payment.dto.response;

/**
 * Answer of the mock card form.
 *
 * @param threeDSecureRequired the card asks for its 3-D Secure code: send it again with the code
 * @param payment              the payment after the attempt
 */
public record MockCardPaymentResponse(boolean threeDSecureRequired, PaymentResponse payment) {
}
