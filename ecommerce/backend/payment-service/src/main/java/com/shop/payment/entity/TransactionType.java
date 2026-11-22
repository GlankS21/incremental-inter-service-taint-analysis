package com.shop.payment.entity;

public enum TransactionType {

    /** An attempt to collect the amount of the order. */
    CHARGE,

    /** Money given back for a captured charge. */
    REFUND
}
