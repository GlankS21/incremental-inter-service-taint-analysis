package com.shop.payment.entity;

import com.shop.common.util.TextUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One operation at the payment provider: a charge attempt or a refund. The id is assigned before the
 * provider is called and sent as its idempotence key, so a repeated call never creates a second
 * operation. Rows are never deleted: they form the transaction history of the payment. Changes are
 * made under the row lock of the {@link Payment}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction extends AuditableEntity implements Persistable<UUID> {

    static final int MAX_REASON_LENGTH = 500;

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private PaymentProvider provider;

    /** Id of the operation at the provider, known once the provider answered. */
    @Column(name = "provider_id", length = 100)
    private String providerId;

    /** For a refund: the charge it gives back. */
    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "confirmation_token")
    private String confirmationToken;

    @Column(name = "failure_reason", length = MAX_REASON_LENGTH)
    private String failureReason;

    @Version
    private Long version;

    @Transient
    private boolean persisted;

    public static PaymentTransaction charge(Payment payment, PaymentProvider provider) {
        return create(payment, TransactionType.CHARGE, provider, null);
    }

    public static PaymentTransaction refund(Payment payment, PaymentTransaction charge) {
        return create(payment, TransactionType.REFUND, charge.getProvider(), charge.getId());
    }

    private static PaymentTransaction create(Payment payment, TransactionType type, PaymentProvider provider,
                                             UUID parentId) {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setId(UUID.randomUUID());
        transaction.setPaymentId(payment.getId());
        transaction.setType(type);
        transaction.setProvider(provider);
        transaction.setParentId(parentId);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setAmount(payment.getAmount());
        transaction.setCurrency(payment.getCurrency());
        return transaction;
    }

    public boolean isPending() {
        return status == TransactionStatus.PENDING;
    }

    public void succeed() {
        status = TransactionStatus.SUCCEEDED;
        failureReason = null;
        confirmationToken = null;
    }

    public void fail(String reason) {
        status = TransactionStatus.FAILED;
        failureReason = TextUtils.truncate(reason, MAX_REASON_LENGTH);
        confirmationToken = null;
    }

    /** The id is assigned, so tell Spring Data to persist (not merge) new rows. */
    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        persisted = true;
    }
}
