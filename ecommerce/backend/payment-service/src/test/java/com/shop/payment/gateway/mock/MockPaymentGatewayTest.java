package com.shop.payment.gateway.mock;

import com.shop.payment.gateway.GatewayException;
import com.shop.payment.gateway.PaymentGateway.CreatePaymentRequest;
import com.shop.payment.gateway.PaymentGateway.CreateRefundRequest;
import com.shop.payment.gateway.PaymentGateway.GatewayPayment;
import com.shop.payment.gateway.PaymentGateway.Status;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPaymentGatewayTest {

    private static final YearMonth VALID = YearMonth.of(2030, 12);

    private final MockPaymentGateway gateway =
            new MockPaymentGateway(Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void paymentWaitsForTheCardWithAWidgetToken() {
        GatewayPayment payment = gateway.createPayment(request("key-1"));

        assertThat(payment.id()).isEqualTo("mock-key-1");
        assertThat(payment.status()).isEqualTo(Status.PENDING);
        assertThat(payment.confirmationToken()).isEqualTo("mock-ct-key-1");
        // Same idempotence key, same payment
        assertThat(gateway.createPayment(request("key-1"))).isEqualTo(payment);
    }

    @Test
    void successfulTestCardPaysAtOnce() {
        gateway.createPayment(request("key-1"));

        MockPaymentGateway.CardOutcome outcome = gateway.pay("mock-key-1", "5555555555554444", VALID, null);

        assertThat(outcome.threeDSecureRequired()).isFalse();
        assertThat(outcome.payment().status()).isEqualTo(Status.SUCCEEDED);
        assertThat(outcome.payment().confirmationToken()).isNull();
        // A final outcome cannot be changed
        assertThat(gateway.pay("mock-key-1", "5555555555554600", VALID, null).payment().status())
                .isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void threeDSecureCardAsksForItsCodeFirst() {
        gateway.createPayment(request("key-1"));

        MockPaymentGateway.CardOutcome challenge = gateway.pay("mock-key-1", "5555555555554477", VALID, null);
        assertThat(challenge.threeDSecureRequired()).isTrue();
        assertThat(challenge.payment().status()).isEqualTo(Status.PENDING);

        assertThat(gateway.pay("mock-key-1", "5555555555554477", VALID, "123").payment().status())
                .isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void concurrentCardsCannotOverwriteTheFirstOutcome() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 500; i++) {
                String id = gateway.createPayment(request("race-" + i)).id();
                CyclicBarrier start = new CyclicBarrier(2);
                Future<GatewayPayment> paid = executor.submit(() -> {
                    start.await();
                    return gateway.pay(id, "5555555555554444", VALID, null).payment();
                });
                Future<GatewayPayment> declined = executor.submit(() -> {
                    start.await();
                    return gateway.pay(id, "5555555555554600", VALID, null).payment();
                });

                // Whichever card came first decides; the other caller is told that outcome
                GatewayPayment first = paid.get(5, TimeUnit.SECONDS);
                assertThat(first.status()).isNotEqualTo(Status.PENDING);
                assertThat(declined.get(5, TimeUnit.SECONDS)).isEqualTo(first);
                assertThat(gateway.getPayment(id)).isEqualTo(first);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void declinedTestCardsGiveTheReasonOfYooKassa() {
        assertThat(declineReason("key-1", "5555555555554600", VALID, null)).isEqualTo("insufficient_funds");
        assertThat(declineReason("key-2", "4889971706588753", VALID, null)).isEqualTo("general_decline");
        assertThat(declineReason("key-3", "2200000000000038", VALID, null)).isEqualTo("card_expired");
        assertThat(declineReason("key-4", "5555555555554626", VALID, null)).isEqualTo("invalid_csc");
        assertThat(declineReason("key-5", "4483274282299972", VALID, null)).isEqualTo("fraud_suspected");
        assertThat(declineReason("key-6", "5555555555554535", VALID, null)).isEqualTo("call_issuer");
        assertThat(declineReason("key-7", "5555555555554592", VALID, "123")).isEqualTo("3d_secure_failed");
    }

    @Test
    void unknownOrExpiredCardsAreDeclined() {
        assertThat(declineReason("key-1", "4000000000000002", VALID, null)).isEqualTo("invalid_card_number");
        assertThat(declineReason("key-2", "5555555555554444", YearMonth.of(2026, 9), null)).isEqualTo("card_expired");
    }

    @Test
    void paymentsForgottenByARestartAreExpired() {
        GatewayPayment payment = gateway.getPayment("mock-lost");

        assertThat(payment.status()).isEqualTo(Status.CANCELED);
        assertThat(payment.cancellationReason()).isEqualTo("expired_on_confirmation");
        assertThatThrownBy(() -> gateway.getPayment("yk-1")).isInstanceOf(GatewayException.class);
    }

    @Test
    void refundsSucceedAtOnce() {
        var refund = gateway.createRefund(new CreateRefundRequest("r-1", "mock-key-1", BigDecimal.TEN, "RUB", "x"));

        assertThat(refund.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(gateway.getRefund(refund.id())).isEqualTo(refund);
    }

    private String declineReason(String key, String card, YearMonth expiry, String code) {
        gateway.createPayment(request(key));
        GatewayPayment payment = gateway.pay("mock-" + key, card, expiry, code).payment();
        assertThat(payment.status()).isEqualTo(Status.CANCELED);
        return payment.cancellationReason();
    }

    private static CreatePaymentRequest request(String key) {
        return new CreatePaymentRequest(key, UUID.randomUUID(), "ORD-1", new BigDecimal("990.00"), "RUB", "Оплата",
                Map.of());
    }
}
