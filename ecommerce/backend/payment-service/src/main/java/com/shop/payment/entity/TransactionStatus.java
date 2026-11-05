package com.shop.payment.entity;

/**
 * {@code PENDING} until the provider reports a final outcome (by webhook, when the customer returns
 * from the payment page, or through the reconciliation job).
 */
public enum TransactionStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
