package com.shop.product.scheduler;

import com.shop.product.service.ReviewReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes the review reminders that have become due (see {@link ReviewReminderService}). Disabled with
 * {@code app.reviews.reminder.enabled=false}.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.reviews.reminder", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReviewReminderJob {

    private final ReviewReminderService reviewReminderService;

    @Scheduled(fixedDelayString = "${app.reviews.reminder.check-interval:PT15M}",
            initialDelayString = "${app.reviews.reminder.initial-delay:PT1M}")
    public void publishDueReminders() {
        reviewReminderService.publishDueReminders();
    }
}
