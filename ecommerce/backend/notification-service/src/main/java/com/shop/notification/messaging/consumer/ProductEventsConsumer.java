package com.shop.notification.messaging.consumer;

import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.ReviewReminderDueEvent;
import com.shop.notification.service.NotificationPreferenceService;
import com.shop.notification.service.NotificationService;
import com.shop.notification.template.NotificationTemplates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Events of product-service; failures are retried and then dead-lettered to
 * {@code notification.product-events.dlq}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RabbitListener(queues = MessagingConstants.NOTIFICATION_PRODUCT_EVENTS_QUEUE)
public class ProductEventsConsumer {

    private final NotificationService notificationService;
    private final NotificationPreferenceService preferenceService;
    private final NotificationTemplates templates;

    /**
     * One e-mail per delivered order, unless the customer turned review reminders off. The event carries
     * no address: the e-mail goes to the address of the customer's latest notification (the order e-mails
     * and «Оцените покупку» all have it). A redelivered or republished reminder has the same event id and
     * is ignored.
     */
    @RabbitHandler
    public void onReviewReminderDue(ReviewReminderDueEvent event) {
        log.info("Received {} (event {}) for order {}",
                MessagingConstants.REVIEW_REMINDER_DUE_KEY, event.eventId(), event.orderNumber());
        if (event.userId() == null || event.items() == null || event.items().isEmpty()) {
            log.warn("Ignoring incomplete {} event {}", MessagingConstants.REVIEW_REMINDER_DUE_KEY, event.eventId());
            return;
        }
        if (!preferenceService.reviewRemindersEnabled(event.userId())) {
            log.info("User {} turned review reminders off, no reminder for order {}", event.userId(),
                    event.orderNumber());
            return;
        }
        Optional<String> recipient = notificationService.findLatestAddress(event.userId());
        if (recipient.isEmpty()) {
            log.warn("No known address of user {}, no review reminder for order {}", event.userId(),
                    event.orderNumber());
            return;
        }
        notificationService.notify(event.eventId(), templates.reviewReminder(event, recipient.get()));
    }
}
