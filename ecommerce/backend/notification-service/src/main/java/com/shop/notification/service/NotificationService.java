package com.shop.notification.service;

import com.shop.common.dto.PageResponse;
import com.shop.notification.dto.response.NotificationResponse;
import com.shop.notification.entity.NotificationStatus;
import com.shop.notification.entity.NotificationType;
import com.shop.notification.template.NotificationTemplates.RenderedNotification;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/**
 * Records and delivers e-mails. Delivery happens outside database transactions; a failed delivery
 * is recorded and retried by a scheduled job.
 */
public interface NotificationService {

    /**
     * Saves the notification of an event and sends it by e-mail, or only records it when it is an in-app
     * notification. An event already handled (same event id) is ignored.
     */
    void notify(UUID eventId, RenderedNotification notification);

    /**
     * @return number of e-mails delivered by this run
     */
    int retryUndelivered();

    PageResponse<NotificationResponse> listOf(UUID userId, Pageable pageable);

    /**
     * The address of the latest notification of the customer that has one: notification-service keeps no
     * other copy of customer addresses, and events such as {@code review.reminder-due} carry none.
     */
    Optional<String> findLatestAddress(UUID userId);

    PageResponse<NotificationResponse> search(NotificationStatus status, NotificationType type, Pageable pageable);

    /**
     * Sends the e-mail again, whatever its number of attempts. Refused while another attempt is in
     * progress, and for a confidential e-mail (password reset link) that was already sent or has expired.
     */
    NotificationResponse resend(UUID notificationId);
}
