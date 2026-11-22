package com.shop.product.messaging.consumer;

import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.OrderDeliveredEvent;
import com.shop.product.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Records the products of every delivered order, which lets their buyer review them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDeliveredConsumer {

    private final PurchaseService purchaseService;

    @RabbitListener(queues = MessagingConstants.PRODUCT_ORDER_DELIVERED_QUEUE)
    public void onOrderDelivered(OrderDeliveredEvent event) {
        log.info("Received {} (event {}) for order {}",
                MessagingConstants.ORDER_DELIVERED_KEY, event.eventId(), event.orderNumber());
        purchaseService.recordDeliveredOrder(event);
    }
}
