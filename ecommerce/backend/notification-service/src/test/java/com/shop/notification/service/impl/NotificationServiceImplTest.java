package com.shop.notification.service.impl;

import com.shop.common.exception.BadRequestException;
import com.shop.common.exception.ConflictException;
import com.shop.common.exception.ResourceNotFoundException;
import com.shop.notification.config.NotificationProperties;
import com.shop.notification.entity.Notification;
import com.shop.notification.entity.NotificationStatus;
import com.shop.notification.entity.NotificationType;
import com.shop.notification.mapper.NotificationMapperImpl;
import com.shop.notification.repository.NotificationRepository;
import com.shop.notification.sender.EmailSender;
import com.shop.notification.template.NotificationTemplates.RenderedNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private EmailSender emailSender;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties(null, "TechShop",
                new NotificationProperties.Retry(3, Duration.ofMinutes(2)), null);
        service = new NotificationServiceImpl(notificationRepository, emailSender, new NotificationMapperImpl(),
                properties, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void notify_storesTheNotificationThenSendsIt() {
        Notification saved = stored(NotificationStatus.PENDING, 0);
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification entity = invocation.getArgument(0);
            entity.setId(saved.getId());
            return entity;
        });
        when(notificationRepository.claim(saved.getId(), List.of(NotificationStatus.PENDING), NOW)).thenReturn(1);
        when(notificationRepository.findById(saved.getId())).thenReturn(Optional.of(saved));

        UUID eventId = UUID.randomUUID();
        service.notify(eventId, rendered("ivan.petrov@example.ru"));

        verify(emailSender).send("ivan.petrov@example.ru", "Subject", "Body");
        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(saved.getSentAt()).isEqualTo(NOW);
        assertThat(saved.getAttempts()).isEqualTo(1);
    }

    @Test
    void notify_ignoresAnEventThatWasAlreadyHandled() {
        UUID eventId = UUID.randomUUID();
        when(notificationRepository.existsByEventId(eventId)).thenReturn(true);

        service.notify(eventId, rendered("ivan.petrov@example.ru"));

        verify(notificationRepository, never()).save(any());
        verifyNoInteractions(emailSender);
    }

    @Test
    void notify_skipsAMissingRecipient() {
        service.notify(UUID.randomUUID(), rendered(" "));

        verifyNoInteractions(notificationRepository, emailSender);
    }

    @Test
    void failedDeliveryIsRecordedForALaterRetry() {
        Notification notification = stored(NotificationStatus.PENDING, 0);
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(notificationRepository.findIdsToRetry(eq(3), eq(NOW.minus(Duration.ofMinutes(2))), any(Pageable.class)))
                .thenReturn(List.of(notification.getId()));
        when(notificationRepository.claimForRetry(notification.getId(), 3, NOW.minus(Duration.ofMinutes(2)), NOW))
                .thenReturn(1);
        doThrow(new MailSendException("Connection refused")).when(emailSender).send(anyString(), anyString(), anyString());

        assertThat(service.retryUndelivered()).isZero();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getLastError()).isEqualTo("MailSendException: Connection refused");
    }

    @Test
    void resend_deliversAFailedNotificationAgain() {
        Notification notification = stored(NotificationStatus.FAILED, 3);
        notification.setLastError("MailSendException: Connection refused");
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(notificationRepository.claim(notification.getId(),
                List.of(NotificationStatus.PENDING, NotificationStatus.FAILED, NotificationStatus.SENT), NOW))
                .thenReturn(1);

        assertThat(service.resend(notification.getId()).status()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getAttempts()).isEqualTo(4);
        assertThat(notification.getLastError()).isNull();
    }

    @Test
    void resend_isRefusedWhileAnotherAttemptIsInProgress() {
        Notification notification = stored(NotificationStatus.SENDING, 1);
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> service.resend(notification.getId())).isInstanceOf(ConflictException.class)
                .hasMessage(NotificationServiceImpl.ALREADY_SENDING);
        verifyNoInteractions(emailSender);
    }

    @Test
    void resend_isRefusedForADeliveredPasswordResetLink() {
        Notification notification = stored(NotificationStatus.SENT, 1);
        notification.setType(NotificationType.PASSWORD_RESET);
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> service.resend(notification.getId())).isInstanceOf(BadRequestException.class)
                .hasMessage(NotificationServiceImpl.NOT_RESENDABLE);
        verify(notificationRepository).claim(notification.getId(),
                List.of(NotificationStatus.PENDING, NotificationStatus.FAILED), NOW);
        verifyNoInteractions(emailSender);
    }

    @Test
    void retry_skipsARowClaimedByAnotherSender() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.findIdsToRetry(eq(3), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(id));

        assertThat(service.retryUndelivered()).isZero();

        verify(notificationRepository, never()).findById(id);
        verifyNoInteractions(emailSender);
    }

    @Test
    void retry_marksAnExpiredLinkInsteadOfSendingIt() {
        Notification notification = stored(NotificationStatus.FAILED, 1);
        notification.setType(NotificationType.PASSWORD_RESET);
        notification.setBody("https://shop.example.com/reset-password?token=secret");
        notification.setExpiresAt(NOW);
        when(notificationRepository.findIdsToRetry(eq(3), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(notification.getId()));
        when(notificationRepository.claimForRetry(eq(notification.getId()), eq(3), any(Instant.class), eq(NOW)))
                .thenReturn(1);
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

        assertThat(service.retryUndelivered()).isZero();

        verifyNoInteractions(emailSender);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.EXPIRED);
        assertThat(notification.getBody()).isEqualTo(Notification.ERASED_BODY);
        assertThat(notification.getAttempts()).isEqualTo(1);
    }

    @Test
    void retry_firstReleasesInterruptedAttemptsAndExpiresOverdueLinks() {
        service.retryUndelivered();

        verify(notificationRepository).releaseInterrupted(NOW.minus(NotificationServiceImpl.SENDING_TIMEOUT),
                NotificationServiceImpl.INTERRUPTED_ERROR, NOW);
        verify(notificationRepository).expireOverdue(List.of(NotificationType.PASSWORD_RESET),
                Notification.ERASED_BODY, NOW);
    }

    @Test
    void aSentPasswordResetLinkIsErased() {
        Notification notification = stored(NotificationStatus.SENDING, 0);
        notification.setType(NotificationType.PASSWORD_RESET);
        notification.setBody("https://shop.example.com/reset-password?token=secret");

        notification.markSent(NOW);

        assertThat(notification.getBody()).isEqualTo(Notification.ERASED_BODY);
    }

    @Test
    void resend_failsForAnUnknownNotification() {
        assertThatThrownBy(() -> service.resend(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("notification.error.not-found");
        verifyNoInteractions(emailSender);
    }

    private static RenderedNotification rendered(String recipient) {
        return new RenderedNotification(NotificationType.WELCOME, UUID.randomUUID(), recipient, null, "Subject", "Body");
    }

    private static Notification stored(NotificationStatus status, int attempts) {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setEventId(UUID.randomUUID());
        notification.setType(NotificationType.WELCOME);
        notification.setRecipient("ivan.petrov@example.ru");
        notification.setSubject("Subject");
        notification.setBody("Body");
        notification.setStatus(status);
        notification.setAttempts(attempts);
        return notification;
    }
}
