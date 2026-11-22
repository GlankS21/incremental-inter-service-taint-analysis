package com.shop.product.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Review reminder ({@code app.reviews.reminder.*}): some days after delivery, a customer who has not
 * reviewed every product of the order gets one e-mail. Every value has a default.
 *
 * @param enabled       whether the reminder job runs
 * @param delayDays     days after delivery before the reminder is sent
 * @param checkInterval delay between two runs of the job
 * @param batchSize     orders handled by one run
 */
@ConfigurationProperties(prefix = "app.reviews.reminder")
public record ReviewReminderProperties(
        Boolean enabled,
        Integer delayDays,
        Duration checkInterval,
        Integer batchSize
) {

    public ReviewReminderProperties {
        enabled = enabled == null || enabled;
        delayDays = delayDays == null ? 3 : delayDays;
        checkInterval = checkInterval == null ? Duration.ofMinutes(15) : checkInterval;
        batchSize = batchSize == null ? 100 : batchSize;
        if (delayDays < 0) {
            throw new IllegalArgumentException("app.reviews.reminder.delay-days must not be negative");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.reviews.reminder.batch-size must be positive");
        }
    }

    public Duration delay() {
        return Duration.ofDays(delayDays);
    }
}
