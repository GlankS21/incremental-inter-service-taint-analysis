package com.shop.payment.repository;

import com.shop.payment.entity.PaymentProvider;
import com.shop.payment.entity.PaymentTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    List<PaymentTransaction> findAllByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    Optional<PaymentTransaction> findByProviderAndProviderId(PaymentProvider provider, String providerId);

    @Query("select t.paymentId from PaymentTransaction t where t.id = :id")
    Optional<UUID> findPaymentIdById(@Param("id") UUID id);

    /**
     * Operations still waiting for the provider, oldest first.
     */
    @Query("""
            select t.id from PaymentTransaction t
            where t.status = com.shop.payment.entity.TransactionStatus.PENDING and t.createdAt < :before
            order by t.createdAt
            """)
    List<UUID> findPendingIdsCreatedBefore(@Param("before") Instant before, Pageable pageable);
}
