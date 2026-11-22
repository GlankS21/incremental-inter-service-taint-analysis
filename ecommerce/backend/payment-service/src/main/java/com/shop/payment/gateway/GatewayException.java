package com.shop.payment.gateway;

import lombok.Getter;

/**
 * A call to the payment provider failed.
 */
@Getter
public class GatewayException extends RuntimeException {

    /** The provider was unreachable or answered with a temporary error. */
    public static final String PROVIDER_UNAVAILABLE = "provider_unavailable";

    /** The provider refused the request (invalid data, credentials, unknown id...). */
    public static final String PROVIDER_REJECTED = "provider_rejected";

    /** Code stored as the failure reason of the transaction. */
    private final String reasonCode;

    /** Repeating the same call (with the same idempotence key) may succeed. */
    private final boolean retryable;

    public GatewayException(String reasonCode, boolean retryable, String message) {
        super(message);
        this.reasonCode = reasonCode;
        this.retryable = retryable;
    }

    public GatewayException(String reasonCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
        this.retryable = retryable;
    }

    public static GatewayException unavailable(String message, Throwable cause) {
        return new GatewayException(PROVIDER_UNAVAILABLE, true, message, cause);
    }

    public static GatewayException rejected(String message) {
        return new GatewayException(PROVIDER_REJECTED, false, message);
    }
}
