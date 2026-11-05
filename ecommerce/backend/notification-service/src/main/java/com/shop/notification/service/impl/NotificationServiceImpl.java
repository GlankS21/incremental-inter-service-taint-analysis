package com.shop.notification.service.impl;

import com.shop.common.dto.PageResponse;
import com.shop.common.exception.BadRequestException;
import com.shop.common.exception.ConflictException;
import com.shop.common.exception.ResourceNotFoundException;
import com.shop.notification.config.NotificationProperties;
import com.shop.notification.dto.response.NotificationResponse;
import com.shop.notification.entity.Notification;
import com.shop.notification.entity.NotificationChannel;
import com.shop.notification.entity.NotificationStatus;
import com.shop.notification.entity.NotificationType;
import com.shop.notification.mapper.NotificationMapper;
import com.shop.notification.repository.NotificationRepository;
import com.shop.notification.sender.EmailSender;
import com.shop.notification.service.NotificationService;
import com.shop.notification.template.NotificationTemplates.RenderedNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    static final int RETRY_BATCH_SIZE = 50;
    /**
     * A row still {@code SENDING} after this delay was abandoned by a crashed sender. Far above the SMTP
     * connection, read and write timeouts (5 s each in config-repo), so a live attempt is never reclaimed.
     */
    static final Duration SENDING_TIMEOUT = Duration.ofMinutes(10);
    static final String INTERRUPTED_ERROR = "Delivery interrupted: the service stopped while sending";
    static final String NOT_FOUND = "notification.error.not-found";
    static final String ALREADY_SENDING = "notification.error.already-sending";
    static final String NOT_RESENDABLE = "notification.error.not-resendable";
    static final String IN_APP_NOT_RESENDABLE = "notification.error.in-app-not-resendable";
    private static final List<NotificationType> CONFIDENTIAL_TYPES = Arrays.stream(NotificationType.values())
            .filter(NotificationType::hasConfidentialBody)
            .toList();

    private final NotificationRepository notificationRepository;
    private final EmailSender emailSender;
    private final NotificationMapper notificationMapper;
    private final NotificationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public void notify(UUID eventId, RenderedNotification notification) {
        boolean inApp = notification.type().channel() == NotificationChannel.IN_APP;
        // An in-app notification is shown in the account of its customer; an e-mail needs an address
        if (inApp ? notification.userId() == null : !StringUtils.hasText(notification.recipient())) {
            log.warn("No recipient for {} notification of event {}, skipped", notification.type(), eventId);
            return;
        }
        UUID notificationId = transactionTemplate.execute(status -> {
            if (notificationRepository.existsByEventId(eventId)) {
                return null;
            }
            Notification entity = new Notification();
            entity.setEventId(eventId);
            entity.setType(notification.type());
            entity.setUserId(notification.userId());
            entity.setRecipient(notification.recipient() == null ? "" : notification.recipient());
            entity.setReference(notification.reference());
            entity.setSubject(notification.subject());
            entity.setBody(notification.body());
            entity.setExpiresAt(notification.expiresAt());
            if (inApp) {
                // Delivered by being recorded: never picked up by the e-mail retry job
                entity.setStatus(NotificationStatus.SENT);
                entity.setSentAt(clock.instant());
            } else {
                entity.setStatus(NotificationStatus.PENDING);
            }
            return notificationRepository.save(entity).getId();
        });
        if (notificationId == null) {
            log.info("Event {} already notified, ignored", eventId);
            return;
        }
        if (inApp) {
            log.info("Recorded in-app {} notification {} for user {}", notification.type(), notificationId,
                    notification.userId());
            return;
        }
        deliver(notificationId, now -> notificationRepository.claim(notificationId,
                List.of(NotificationStatus.PENDING), now));
    }

    @Override
    public int retryUndelivered() {
        Instant now = clock.instant();
        transactionTemplate.executeWithoutResult(status -> {
            int interrupted = notificationRepository.releaseInterrupted(now.minus(SENDING_TIMEOUT),
                    INTERRUPTED_ERROR, now);
            int expired = notificationRepository.expireOverdue(CONFIDENTIAL_TYPES, Notification.ERASED_BODY, now);
            if (interrupted > 0 || expired > 0) {
                log.info("{} interrupted delivery attempt(s) marked failed, {} e-mail(s) expired", interrupted, expired);
            }
        });
        int maxAttempts = properties.retry().maxAttempts();
        Instant pendingBefore = now.minus(properties.retry().interval());
        List<UUID> ids = notificationRepository.findIdsToRetry(maxAttempts, pendingBefore,
                PageRequest.of(0, RETRY_BATCH_SIZE));
        int delivered = 0;
        for (UUID id : ids) {
            if (deliver(id, at -> notificationRepository.claimForRetry(id, maxAttempts, pendingBefore, at))
                    == Delivery.SENT) {
                delivered++;
            }
        }
        return delivered;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> listOf(UUID userId, Pageable pageable) {
        return PageResponse.from(notificationRepository.findAllByUserId(userId, pageable),
                notificationMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findLatestAddress(UUID userId) {
        return notificationRepository.findFirstByUserIdAndRecipientNotOrderByCreatedAtDesc(userId, "")
                .map(Notification::getRecipient);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> search(NotificationStatus status, NotificationType type,
                                                     Pageable pageable) {
        Specification<Notification> specification = Specification.allOf(
                (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status),
                (root, query, cb) -> type == null ? null : cb.equal(root.get("type"), type));
        return PageResponse.from(notificationRepository.findAll(specification, pageable),
                notificationMapper::toResponse);
    }

    /**
     * A confidential body is erased once sent, so such an e-mail can only be resent while undelivered.
     */
    @Override
    public NotificationResponse resend(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> ResourceNotFoundException.of(NOT_FOUND));
        if (notification.getType().channel() == NotificationChannel.IN_APP) {
            throw new BadRequestException(IN_APP_NOT_RESENDABLE);
        }
        List<NotificationStatus> resendable = notification.getType().hasConfidentialBody()
                ? List.of(NotificationStatus.PENDING, NotificationStatus.FAILED)
                : List.of(NotificationStatus.PENDING, NotificationStatus.FAILED, NotificationStatus.SENT);
        Delivery delivery = deliver(notificationId, now -> notificationRepository.claim(notificationId,
                resendable, now));
        if (delivery == Delivery.NOT_CLAIMED) {
            boolean sending = notificationRepository.findById(notificationId)
                    .map(current -> current.getStatus() == NotificationStatus.SENDING)
                    .orElse(false);
            throw sending ? new ConflictException(ALREADY_SENDING) : new BadRequestException(NOT_RESENDABLE);
        }
        if (delivery == Delivery.EXPIRED) {
            throw new BadRequestException(NOT_RESENDABLE);
        }
        return notificationRepository.findById(notificationId)
                .map(notificationMapper::toResponse)
                .orElseThrow(() -> ResourceNotFoundException.of(NOT_FOUND));
    }

    /**
     * Claims the row, then sends outside any transaction (an SMTP server may be slow) and records the
     * outcome. An e-mail whose link has expired is marked {@code EXPIRED} instead of being sent.
     *
     * @param claim moves the row to {@code SENDING} at the given instant, returns the number of rows claimed
     */
    private Delivery deliver(UUID notificationId, Function<Instant, Integer> claim) {
        Integer claimed = transactionTemplate.execute(status -> claim.apply(clock.instant()));
        if (claimed == null || claimed == 0) {
            return Delivery.NOT_CLAIMED;
        }
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            return Delivery.NOT_CLAIMED;
        }
        if (notification.isExpired(clock.instant())) {
            updateStatus(notificationId, Notification::markExpired);
            log.info("{} e-mail {} expired before it was sent, dropped", notification.getType(), notificationId);
            return Delivery.EXPIRED;
        }
        try {
            emailSender.send(notification.getRecipient(), notification.getSubject(), notification.getBody());
        } catch (RuntimeException ex) {
            String error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            updateStatus(notificationId, entity -> entity.markFailed(error));
            log.warn("Could not send {} e-mail {} to {} (attempt {}): {}", notification.getType(), notificationId,
                    notification.getRecipient(), notification.getAttempts() + 1, ex.getMessage());
            return Delivery.FAILED;
        }
        updateStatus(notificationId, entity -> entity.markSent(clock.instant()));
        log.info("Sent {} e-mail {} to {}", notification.getType(), notificationId, notification.getRecipient());
        return Delivery.SENT;
    }

    private void updateStatus(UUID notificationId, Consumer<Notification> change) {
        transactionTemplate.executeWithoutResult(status ->
                notificationRepository.findById(notificationId).ifPresent(change));
    }

    private enum Delivery {
        /** Another sender owns the row, or its status does not allow sending. */
        NOT_CLAIMED,
        SENT,
        FAILED,
        EXPIRED
    }
}
