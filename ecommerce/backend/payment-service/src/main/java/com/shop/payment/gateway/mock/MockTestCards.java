package com.shop.payment.gateway.mock;

import java.util.Map;
import java.util.Optional;

/**
 * Test bank cards of YooKassa (developer documentation, "Тестирование": test bank cards), with the
 * outcome YooKassa gives them in a test shop. The mock provider answers the same way, so a scenario
 * can be tried locally and then again with a real test shop.
 */
final class MockTestCards {

    /**
     * @param threeDSecure  the payment asks for a 3-D Secure code (any digits are accepted)
     * @param declineReason {@code cancellation_details.reason} of the declined payment; {@code null} when it succeeds
     */
    record TestCard(boolean threeDSecure, String declineReason) {
    }

    private static final TestCard SUCCESS = new TestCard(false, null);
    private static final TestCard SUCCESS_3DS = new TestCard(true, null);

    private static final Map<String, TestCard> CARDS = Map.ofEntries(
            // Successful payments
            Map.entry("5555555555554477", SUCCESS_3DS),
            Map.entry("5555555555554444", SUCCESS),
            Map.entry("6759649826438453", SUCCESS),
            Map.entry("4793128161644804", SUCCESS_3DS),
            Map.entry("4111111111111111", SUCCESS),
            Map.entry("4175001000000017", SUCCESS),
            Map.entry("2200000000000004", SUCCESS_3DS),
            Map.entry("2202474301322987", SUCCESS),
            Map.entry("370000000000002", SUCCESS),
            Map.entry("3528000700000000", SUCCESS),
            Map.entry("36700102000000", SUCCESS),
            // Declined by the payment network: Mastercard, Visa, Mir
            declined3ds("5555555555554592", "3d_secure_failed"),
            declined3ds("4839665499603842", "3d_secure_failed"),
            declined3ds("2200000000000012", "3d_secure_failed"),
            declined("5555555555554535", "call_issuer"),
            declined("4926946416239025", "call_issuer"),
            declined("2200000000000020", "call_issuer"),
            declined("5555555555554543", "card_expired"),
            declined("4141435412630840", "card_expired"),
            declined("2200000000000038", "card_expired"),
            declined("5555555555554568", "fraud_suspected"),
            declined("4483274282299972", "fraud_suspected"),
            declined("2200000000000046", "fraud_suspected"),
            declined("5555555555554527", "general_decline"),
            declined("4889971706588753", "general_decline"),
            declined("2202202212312379", "general_decline"),
            declined("5555555555554600", "insufficient_funds"),
            declined("4562265587712390", "insufficient_funds"),
            declined("2200000000000053", "insufficient_funds"),
            declined("5555555555554618", "invalid_card_number"),
            declined("4951017853630544", "invalid_card_number"),
            declined("2201382000000013", "invalid_card_number"),
            declined("5555555555554626", "invalid_csc"),
            declined("4194180666146368", "invalid_csc"),
            declined("2200770212727079", "invalid_csc"),
            declined("5555555555554501", "issuer_unavailable"),
            declined("4654130848359150", "issuer_unavailable"),
            declined("2201382000000021", "issuer_unavailable"),
            declined("5555555555554576", "payment_method_limit_exceeded"),
            declined("4565231022577548", "payment_method_limit_exceeded"),
            declined("2201382000000039", "payment_method_limit_exceeded"),
            declined("5555555555554550", "payment_method_restricted"),
            declined("4233961169071671", "payment_method_restricted"),
            declined("2201382000000047", "payment_method_restricted"),
            // Declined by YooKassa itself
            declined("5555555555554584", "country_forbidden"),
            declined("4969751510013864", "country_forbidden"),
            declined("2201382000000054", "country_forbidden"),
            declined("5555555555554634", "fraud_suspected"),
            declined("4119098878796485", "fraud_suspected"),
            declined("2201696981989955", "fraud_suspected"));

    private MockTestCards() {
    }

    static Optional<TestCard> find(String cardNumber) {
        return Optional.ofNullable(CARDS.get(cardNumber));
    }

    private static Map.Entry<String, TestCard> declined(String number, String reason) {
        return Map.entry(number, new TestCard(false, reason));
    }

    private static Map.Entry<String, TestCard> declined3ds(String number, String reason) {
        return Map.entry(number, new TestCard(true, reason));
    }
}
