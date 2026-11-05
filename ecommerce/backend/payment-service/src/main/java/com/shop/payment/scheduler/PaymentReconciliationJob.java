package com.shop.payment.scheduler;

import com.shop.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net for lost or late provider notifications (e.g. no public webhook address when running
 * locally): asks the provider about operations still pending after a while.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciliationJob {

    private final PaymentService paymentService;

    @Scheduled(fixedDelayString = "${app.payment.reconciliation.interval:1m}",
            initialDelayString = "${app.payment.reconciliation.interval:1m}")
    public void reconcile() {
        try {
            int checked = paymentService.reconcilePendingTransactions();
            if (checked > 0) {
                log.info("Reconciliation checked {} pending transaction(s)", checked);
            }
        } catch (RuntimeException ex) {
            // e.g. database unavailable; the next run tries again
            log.error("Payment reconciliation run failed", ex);
        }
    }
}
