package com.shop.common.messaging;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Settings of {@link DeadLetterRedriver}.
 *
 * @param enabled      whether dead-lettered messages are moved back to their queue
 * @param initialDelay delay after startup before the first run
 * @param interval     delay between two runs; it is also the minimum pause before a message is retried
 * @param maxRedrives  how many times a message is moved back before it stays in the DLQ for good
 * @param batchSize    messages read from one DLQ per run
 */
@Validated
@ConfigurationProperties(prefix = "app.messaging.dead-letter-redrive")
public record DeadLetterRedriveProperties(
        Boolean enabled,
        Duration initialDelay,
        Duration interval,
        @Min(0) Integer maxRedrives,
        @Min(1) Integer batchSize
) {

    public DeadLetterRedriveProperties {
        enabled = enabled == null || enabled;
        initialDelay = initialDelay == null ? Duration.ofMinutes(1) : initialDelay;
        interval = interval == null ? Duration.ofMinutes(5) : interval;
        maxRedrives = maxRedrives == null ? 5 : maxRedrives;
        batchSize = batchSize == null ? 100 : batchSize;
    }
}
