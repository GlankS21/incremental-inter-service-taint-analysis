package com.shop.payment.dto.response;

import com.shop.payment.entity.PaymentProvider;
import com.shop.payment.entity.TransactionStatus;
import com.shop.payment.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * @param providerId id of the operation at the payment provider
 * @param parentId   for a refund: the charge it gives back
 * @param failureMessage text of {@code failureReason} for the customer, in Russian
 */
public record PaymentTransactionResponse(
        UUID id,
        TransactionType type,
        PaymentProvider provider,
        String providerId,
        UUID parentId,
        TransactionStatus status,
        BigDecimal amount,
        String currency,
        String failureReason,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
