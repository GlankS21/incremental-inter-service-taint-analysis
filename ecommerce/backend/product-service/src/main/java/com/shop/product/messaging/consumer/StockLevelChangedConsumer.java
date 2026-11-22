package com.shop.product.messaging.consumer;

import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.StockLevelChangedEvent;
import com.shop.product.service.StockLevelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the "in stock" flag of the products (search ranking, catalogue badge) in line with inventory-service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockLevelChangedConsumer {

    private final StockLevelService stockLevelService;

    @RabbitListener(queues = MessagingConstants.PRODUCT_STOCK_LEVEL_CHANGED_QUEUE)
    public void onStockLevelChanged(StockLevelChangedEvent event) {
        log.debug("Received {} (event {}) for product {}: {} available",
                MessagingConstants.STOCK_LEVEL_CHANGED_KEY, event.eventId(), event.productId(),
                event.availableQuantity());
        stockLevelService.applyStockLevel(event);
    }
}
