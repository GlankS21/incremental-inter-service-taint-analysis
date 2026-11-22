package com.shop.payment.entity;

/**
 * Outcome of a provider notification.
 */
public enum NotificationStatus {
    /** Stored, being handled. */
    RECEIVED,
    /** The final state of the notified object was verified with the provider and applied. */
    PROCESSED,
    /** The same final notification was already processed. */
    DUPLICATE,
    /** Unknown object, unsupported event, or the provider does not confirm a final state yet. */
    IGNORED,
    /** Could not be verified; the provider sends it again. */
    FAILED
}
