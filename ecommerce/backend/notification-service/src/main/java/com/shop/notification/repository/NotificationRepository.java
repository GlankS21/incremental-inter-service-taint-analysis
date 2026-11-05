package com.shop.notification.repository;

import com.shop.notification.entity.Notification;
import com.shop.notification.entity.NotificationStatus;
import com.shop.notification.entity.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>,
        JpaSpecificationExecutor<Notification> {

    boolean existsByEventId(UUID eventId);

    Page<Notification> findAllByUserId(UUID userId, Pageable pageable);

    Optional<Notification> findFirstByUserIdAndRecipientNotOrderByCreatedAtDesc(UUID userId, String excludedRecipient);

    /**
     * E-mails to deliver again: failed ones with attempts left, and pending ones whose first attempt
     * never happened (the service stopped between saving and sending).
     */
    @Query("""
            select n.id from Notification n
            where (n.status = com.shop.notification.entity.NotificationStatus.FAILED and n.attempts < :maxAttempts)
               or (n.status = com.shop.notification.entity.NotificationStatus.PENDING and n.createdAt < :pendingBefore)
            order by n.createdAt
            """)
    List<UUID> findIdsToRetry(@Param("maxAttempts") int maxAttempts,
                              @Param("pendingBefore") Instant pendingBefore,
                              Pageable pageable);

    /**
     * Takes ownership of a delivery attempt: only the caller that moves the row to {@code SENDING}
     * sends it, so the retry job, another instance and an administrator never send the same e-mail twice.
     *
     * @return 1 if claimed, 0 if the row is not in one of {@code statuses}
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Notification n
            set n.status = com.shop.notification.entity.NotificationStatus.SENDING,
                n.updatedAt = :now, n.version = n.version + 1
            where n.id = :id and n.status in :statuses
            """)
    int claim(@Param("id") UUID id,
              @Param("statuses") Collection<NotificationStatus> statuses,
              @Param("now") Instant now);

    /**
     * {@link #claim} with the conditions of {@link #findIdsToRetry}, re-checked atomically.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Notification n
            set n.status = com.shop.notification.entity.NotificationStatus.SENDING,
                n.updatedAt = :now, n.version = n.version + 1
            where n.id = :id
              and ((n.status = com.shop.notification.entity.NotificationStatus.FAILED and n.attempts < :maxAttempts)
                or (n.status = com.shop.notification.entity.NotificationStatus.PENDING and n.createdAt < :pendingBefore))
            """)
    int claimForRetry(@Param("id") UUID id,
                      @Param("maxAttempts") int maxAttempts,
                      @Param("pendingBefore") Instant pendingBefore,
                      @Param("now") Instant now);

    /**
     * Attempts interrupted by a crash (still {@code SENDING} long after the claim) count as failed,
     * so that they are retried like any other failure.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Notification n
            set n.status = com.shop.notification.entity.NotificationStatus.FAILED,
                n.attempts = n.attempts + 1, n.lastError = :error,
                n.updatedAt = :now, n.version = n.version + 1
            where n.status = com.shop.notification.entity.NotificationStatus.SENDING and n.updatedAt < :claimedBefore
            """)
    int releaseInterrupted(@Param("claimedBefore") Instant claimedBefore,
                           @Param("error") String error,
                           @Param("now") Instant now);

    /**
     * Undelivered e-mails whose link has expired are never sent; a confidential body is erased.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Notification n
            set n.status = com.shop.notification.entity.NotificationStatus.EXPIRED,
                n.body = case when n.type in :confidentialTypes then :erasedBody else n.body end,
                n.updatedAt = :now, n.version = n.version + 1
            where n.expiresAt <= :now
              and n.status in (com.shop.notification.entity.NotificationStatus.PENDING,
                               com.shop.notification.entity.NotificationStatus.FAILED)
            """)
    int expireOverdue(@Param("confidentialTypes") Collection<NotificationType> confidentialTypes,
                      @Param("erasedBody") String erasedBody,
                      @Param("now") Instant now);
}
