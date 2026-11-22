package com.shop.product.messaging.publisher;

import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.ReviewReminderDueEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code review.reminder-due} and waits for the broker to confirm it, so that the reminder job
 * marks an order as reminded only once the event is safely in RabbitMQ (requires
 * {@code spring.rabbitmq.publisher-confirm-type: simple}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewReminderPublisher {

    static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitTemplate rabbitTemplate;

    /**
     * @throws org.springframework.amqp.AmqpException when the broker cannot be reached or does not confirm
     */
    public void publish(ReviewReminderDueEvent event) {
        rabbitTemplate.invoke(operations -> {
            operations.convertAndSend(MessagingConstants.PRODUCT_EXCHANGE,
                    MessagingConstants.REVIEW_REMINDER_DUE_KEY, event);
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
        log.info("Published {} (event {}) for order {}: {} product(s)", MessagingConstants.REVIEW_REMINDER_DUE_KEY,
                event.eventId(), event.orderId(), event.items().size());
    }
}
