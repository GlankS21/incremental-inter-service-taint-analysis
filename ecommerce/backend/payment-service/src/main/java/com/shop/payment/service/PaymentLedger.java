package com.shop.payment.service;

import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.PaymentFailedEvent;
import com.shop.common.event.PaymentRefundedEvent;
import com.shop.common.event.PaymentSucceededEvent;
import com.shop.payment.entity.Payment;
import com.shop.payment.entity.PaymentProvider;
import com.shop.payment.entity.PaymentStatus;
import com.shop.payment.entity.PaymentTransaction;
import com.shop.payment.entity.TransactionStatus;
import com.shop.payment.gateway.PaymentGateway.GatewayPayment;
import com.shop.payment.gateway.PaymentGateway.GatewayRefund;
import com.shop.payment.messaging.outbox.OutboxService;
import com.shop.payment.repository.PaymentRepository;
import com.shop.payment.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * State changes of a payment and its transactions, with the saga events they produce written to the
 * outbox in the caller's transaction. Callers hold the row lock of the payment. No method calls the
 * provider: operations to send are returned and sent by the caller after the commit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentLedger {

    /** Declines that end the saga: the order is cancelled. */
    public static final String INVALID_AMOUNT = "invalid_amount";
    public static final String UNSUPPORTED_CURRENCY = "unsupported_currency";
    public static final String UNSUPPORTED_METHOD = "unsupported_method";

    /** Reasons recorded on the payment or its transactions. */
    public static final String ORDER_CANCELLED = "order_cancelled";
    public static final String NOTHING_COLLECTED = "cash_not_collected";
    public static final String EXPIRED = "expired";

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final OutboxService outboxService;
    private final Clock clock;
    private final PaymentFailureMessages failureMessages;

    /**
     * Final decline before any attempt (invalid request): order-service cancels the order. The payment
     * keeps the reason code; the event carries its Russian text, shown to the customer as the reason
     * of the cancellation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void decline(Payment payment, String reason) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(reason);
        paymentRepository.save(payment);
        outboxService.enqueue(MessagingConstants.PAYMENT_EXCHANGE, MessagingConstants.PAYMENT_FAILED_KEY,
                new PaymentFailedEvent(UUID.randomUUID(), clock.instant(), payment.getId(), payment.getOrderId(),
                        payment.getOrderNumber(), payment.getAmount(), payment.getCurrency(),
                        failureMessages.describe(reason)),
                payment.getOrderId());
        log.warn("Payment of order {} declined: {}", payment.getOrderNumber(), reason);
    }

    /**
     * Cash on delivery: accepted at once, the courier collects the money.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acceptCashOnDelivery(Payment payment) {
        PaymentTransaction charge = PaymentTransaction.charge(payment, PaymentProvider.COD);
        charge.setProviderId("COD-" + payment.getOrderNumber());
        charge.succeed();
        transactionRepository.save(charge);
        payment.setProvider(PaymentProvider.COD);
        payment.setCurrentChargeId(charge.getId());
        capture(payment, charge);
    }

    /**
     * New charge attempt, to be sent to the provider after the commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentTransaction openCharge(Payment payment, PaymentProvider provider) {
        PaymentTransaction charge = transactionRepository.save(PaymentTransaction.charge(payment, provider));
        payment.setProvider(provider);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setCurrentChargeId(charge.getId());
        payment.setConfirmationToken(null);
        payment.setFailureReason(null);
        payment.setAttempts(payment.getAttempts() + 1);
        paymentRepository.save(payment);
        return charge;
    }

    /**
     * Applies the state of a charge read from the provider. Repeated states change nothing.
     *
     * @return a refund to send when the money was captured although the order no longer needs it
     * (order cancelled meanwhile, or another attempt already captured)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PaymentTransaction> applyCharge(Payment payment, PaymentTransaction charge, GatewayPayment state) {
        if (charge.getProviderId() == null) {
            charge.setProviderId(state.id());
        }
        if (state.amount() != null && charge.getAmount().compareTo(state.amount()) != 0) {
            log.error("Provider reports {} {} for charge {} of order {} instead of {}; state ignored", state.amount(),
                    state.currency(), charge.getId(), payment.getOrderNumber(), charge.getAmount());
            return Optional.empty();
        }
        switch (state.status()) {
            case PENDING, WAITING_FOR_CAPTURE -> {
                if (charge.isPending() && state.confirmationToken() != null) {
                    charge.setConfirmationToken(state.confirmationToken());
                    if (payment.isCurrentCharge(charge) && payment.getStatus() == PaymentStatus.PENDING) {
                        payment.setConfirmationToken(state.confirmationToken());
                    }
                }
                return Optional.empty();
            }
            case CANCELED -> {
                if (charge.isPending()) {
                    failCharge(payment, charge, state.cancellationReason());
                }
                return Optional.empty();
            }
            case SUCCEEDED -> {
                if (charge.getStatus() == TransactionStatus.SUCCEEDED) {
                    return Optional.empty();
                }
                // Also when the charge was given up (expired): the money was captured after all
                charge.succeed();
                if (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.FAILED) {
                    payment.setCurrentChargeId(charge.getId());
                    capture(payment, charge);
                    return Optional.empty();
                }
                log.warn("Charge {} of order {} captured while the payment is {}; it is refunded", charge.getId(),
                        payment.getOrderNumber(), payment.getStatus());
                if (payment.getStatus() == PaymentStatus.CANCELLED) {
                    // The order was cancelled before the money arrived. The late charge becomes the payment,
                    // so that its refund reports payment.refunded: the customer learns of the charge and refund
                    payment.setCurrentChargeId(charge.getId());
                    payment.setTransactionId(charge.getProviderId());
                    payment.setPaidAt(clock.instant());
                    payment.setStatus(PaymentStatus.SUCCEEDED);
                }
                return Optional.of(openRefund(payment, charge));
            }
        }
        return Optional.empty();
    }

    /**
     * The provider refused or could not create the charge.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void failCharge(Payment payment, PaymentTransaction charge, String reason) {
        charge.fail(reason);
        if (payment.isCurrentCharge(charge) && payment.getStatus() == PaymentStatus.PENDING) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(reason);
            payment.setConfirmationToken(null);
            log.info("Payment attempt {} of order {} failed: {}", payment.getAttempts(), payment.getOrderNumber(), reason);
        }
    }

    /**
     * The order was cancelled: nothing may be collected from now on.
     *
     * @return the refund to send when the payment was captured
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PaymentTransaction> cancel(Payment payment) {
        switch (payment.getStatus()) {
            case PENDING, FAILED -> {
                payment.setStatus(PaymentStatus.CANCELLED);
                payment.setFailureReason(ORDER_CANCELLED);
                payment.setConfirmationToken(null);
                log.info("Order {} cancelled before payment; a late capture will be refunded", payment.getOrderNumber());
                return Optional.empty();
            }
            case SUCCEEDED -> {
                PaymentTransaction charge = transactionRepository.findById(payment.getCurrentChargeId()).orElseThrow();
                if (charge.getProvider() == PaymentProvider.COD) {
                    payment.setStatus(PaymentStatus.CANCELLED);
                    payment.setFailureReason(NOTHING_COLLECTED);
                    log.info("Cash-on-delivery order {} cancelled, nothing to refund", payment.getOrderNumber());
                    return Optional.empty();
                }
                return Optional.of(openRefund(payment, charge));
            }
            default -> {
                log.info("Payment of order {} is {}, nothing to cancel", payment.getOrderNumber(), payment.getStatus());
                return Optional.empty();
            }
        }
    }

    /**
     * Refund of a captured charge, to be sent to the provider after the commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentTransaction openRefund(Payment payment, PaymentTransaction charge) {
        PaymentTransaction refund = transactionRepository.save(PaymentTransaction.refund(payment, charge));
        if (payment.isCurrentCharge(charge) && payment.getStatus() == PaymentStatus.SUCCEEDED) {
            payment.setStatus(PaymentStatus.REFUND_PENDING);
        }
        log.info("Refund {} of charge {} (order {}) opened", refund.getId(), charge.getId(), payment.getOrderNumber());
        return refund;
    }

    /**
     * Applies the state of a refund read from the provider. Repeated states change nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyRefund(Payment payment, PaymentTransaction refund, GatewayRefund state) {
        if (refund.getProviderId() == null) {
            refund.setProviderId(state.id());
        }
        switch (state.status()) {
            case SUCCEEDED -> {
                if (refund.getStatus() == TransactionStatus.SUCCEEDED) {
                    return;
                }
                refund.succeed();
                if (refundsCurrentCharge(payment, refund)) {
                    Instant now = clock.instant();
                    payment.setStatus(PaymentStatus.REFUNDED);
                    payment.setRefundTransactionId(refund.getProviderId());
                    payment.setRefundedAt(now);
                    payment.setFailureReason(null);
                    outboxService.enqueue(MessagingConstants.PAYMENT_EXCHANGE, MessagingConstants.PAYMENT_REFUNDED_KEY,
                            new PaymentRefundedEvent(UUID.randomUUID(), now, payment.getId(), payment.getOrderId(),
                                    payment.getOrderNumber(), refund.getProviderId(), refund.getAmount(),
                                    refund.getCurrency()),
                            payment.getOrderId());
                }
                log.info("Refund {} of order {} succeeded", refund.getProviderId(), payment.getOrderNumber());
            }
            case CANCELED -> {
                if (refund.isPending()) {
                    failRefund(payment, refund, state.cancellationReason());
                }
            }
            case PENDING, WAITING_FOR_CAPTURE -> {
                // still processed by the provider
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void failRefund(Payment payment, PaymentTransaction refund, String reason) {
        refund.fail(reason);
        if (refundsCurrentCharge(payment, refund)) {
            payment.setStatus(PaymentStatus.REFUND_FAILED);
            payment.setFailureReason(reason);
        }
        log.error("Refund {} of order {} failed: {}; it must be handled manually", refund.getId(),
                payment.getOrderNumber(), reason);
    }

    private boolean refundsCurrentCharge(Payment payment, PaymentTransaction refund) {
        return refund.getParentId().equals(payment.getCurrentChargeId())
                && (payment.getStatus() == PaymentStatus.REFUND_PENDING
                || payment.getStatus() == PaymentStatus.REFUND_FAILED);
    }

    private void capture(Payment payment, PaymentTransaction charge) {
        Instant now = clock.instant();
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setTransactionId(charge.getProviderId());
        payment.setConfirmationToken(null);
        payment.setFailureReason(null);
        payment.setPaidAt(now);
        paymentRepository.save(payment);
        outboxService.enqueue(MessagingConstants.PAYMENT_EXCHANGE, MessagingConstants.PAYMENT_SUCCEEDED_KEY,
                new PaymentSucceededEvent(UUID.randomUUID(), now, payment.getId(), payment.getOrderId(),
                        payment.getOrderNumber(), charge.getProviderId(), payment.getAmount(), payment.getCurrency()),
                payment.getOrderId());
        log.info("Payment of order {} captured ({} {}, transaction {})", payment.getOrderNumber(), payment.getAmount(),
                payment.getCurrency(), charge.getProviderId());
    }
}
