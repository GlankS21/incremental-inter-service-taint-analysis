package com.shop.payment.entity;

/**
 * Who processed a transaction.
 */
public enum PaymentProvider {

    /** Cash on delivery, no payment provider involved. */
    COD,

    /** Local imitation of a payment page ({@code PAYMENT_PROVIDER=mock}). */
    MOCK,

    /** YooKassa API v3 ({@code PAYMENT_PROVIDER=yookassa}). */
    YOOKASSA,

    /** Simulated gateway of the first version; only on payments made before the provider integration. */
    LEGACY
}
