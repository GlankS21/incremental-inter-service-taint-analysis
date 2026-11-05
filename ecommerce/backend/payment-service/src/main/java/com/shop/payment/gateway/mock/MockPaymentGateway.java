package com.shop.payment.gateway.mock;

import com.shop.payment.entity.PaymentProvider;
import com.shop.payment.gateway.GatewayException;
import com.shop.payment.gateway.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Imitation of YooKassa for local runs without a YooKassa account ({@code PAYMENT_PROVIDER=mock}). The
 * website shows a card form in place of the YooKassa widget and sends the card to {@link #pay}; the
 * outcome follows the table of YooKassa test cards ({@link MockTestCards}). Refunds succeed at once.
 * Card data is never stored or logged.
 * <p>
 * State is kept in memory: after a restart, payments still waiting for the customer are reported as
 * expired (as YooKassa does with abandoned payments) and the customer can pay again.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    static final String PAYMENT_PREFIX = "mock-";
    static final String TOKEN_PREFIX = "mock-ct-";
    static final String REFUND_PREFIX = "mock-refund-";
    static final String UNKNOWN_CARD_REASON = "invalid_card_number";
    static final String EXPIRED_CARD_REASON = "card_expired";
    static final String EXPIRED_REASON = "expired_on_confirmation";

    private final Clock clock;
    private final Map<String, GatewayPayment> payments = new ConcurrentHashMap<>();
    private final Map<String, GatewayRefund> refunds = new ConcurrentHashMap<>();

    public MockPaymentGateway(Clock clock) {
        this.clock = clock;
        log.warn("Payments use the MOCK provider: no money is charged (set PAYMENT_PROVIDER=yookassa for YooKassa)");
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.MOCK;
    }

    @Override
    public GatewayPayment createPayment(CreatePaymentRequest request) {
        String id = PAYMENT_PREFIX + request.idempotenceKey();
        // Same idempotence key, same payment
        return payments.computeIfAbsent(id, key -> new GatewayPayment(key, Status.PENDING, request.amount(),
                request.currency(), TOKEN_PREFIX + request.idempotenceKey(), null, request.metadata()));
    }

    @Override
    public GatewayPayment getPayment(String paymentId) {
        GatewayPayment payment = payments.get(paymentId);
        if (payment != null) {
            return payment;
        }
        if (!paymentId.startsWith(PAYMENT_PREFIX)) {
            throw GatewayException.rejected("Unknown mock payment " + paymentId);
        }
        return new GatewayPayment(paymentId, Status.CANCELED, null, null, null, EXPIRED_REASON, null);
    }

    @Override
    public GatewayRefund createRefund(CreateRefundRequest request) {
        String id = REFUND_PREFIX + request.idempotenceKey();
        return refunds.computeIfAbsent(id, key -> new GatewayRefund(key, request.paymentId(), Status.SUCCEEDED,
                request.amount(), request.currency(), null));
    }

    @Override
    public GatewayRefund getRefund(String refundId) {
        GatewayRefund refund = refunds.get(refundId);
        if (refund == null) {
            throw GatewayException.rejected("Unknown mock refund " + refundId);
        }
        return refund;
    }

    /**
     * Pays a pending payment with a card. A card that asks for 3-D Secure is first answered with
     * {@link CardOutcome#threeDSecureRequired()}; the form then sends the card again with the code. A
     * payment that is already final keeps its outcome.
     *
     * @param threeDSecureCode code typed on the imitated bank page; any digits are accepted
     */
    public CardOutcome pay(String paymentId, String cardNumber, YearMonth expiry, String threeDSecureCode) {
        GatewayPayment current = getPayment(paymentId);
        if (current.status() != Status.PENDING) {
            return new CardOutcome(current, false);
        }
        MockTestCards.TestCard card = MockTestCards.find(cardNumber).orElse(null);
        String declineReason;
        if (card == null) {
            // Only the test cards are accepted, as in a YooKassa test shop
            declineReason = UNKNOWN_CARD_REASON;
        } else if (expiry.isBefore(YearMonth.now(clock))) {
            declineReason = EXPIRED_CARD_REASON;
        } else if (card.threeDSecure() && !StringUtils.hasText(threeDSecureCode)) {
            return new CardOutcome(current, true);
        } else {
            declineReason = card.declineReason();
        }
        GatewayPayment completed = new GatewayPayment(paymentId,
                declineReason == null ? Status.SUCCEEDED : Status.CANCELED,
                current.amount(), current.currency(), null, declineReason, current.metadata());
        // Atomic: of two cards sent at the same time only the first completes the payment
        GatewayPayment result = payments.computeIfPresent(paymentId,
                (id, latest) -> latest.status() == Status.PENDING ? completed : latest);
        if (result != completed) {
            return new CardOutcome(getPayment(paymentId), false);
        }
        log.info("Mock payment {} {}", paymentId, declineReason == null ? "paid" : "declined: " + declineReason);
        return new CardOutcome(completed, false);
    }

    /**
     * @param payment              the payment after the attempt
     * @param threeDSecureRequired the card asks for its 3-D Secure code; the payment is unchanged
     */
    public record CardOutcome(GatewayPayment payment, boolean threeDSecureRequired) {
    }
}
