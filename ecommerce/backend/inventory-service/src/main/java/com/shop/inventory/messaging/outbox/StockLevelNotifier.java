package com.shop.inventory.messaging.outbox;

import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.StockLevelChangedEvent;
import com.shop.inventory.entity.InventoryItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Tells product-service how many units of a product can still be sold, through the outbox of the
 * transaction that changed the stock. Only real changes of the available quantity are published: a
 * sale commit lowers both on-hand and reserved units and leaves it unchanged.
 */
@Component
@RequiredArgsConstructor
public class StockLevelNotifier {

    private final OutboxService outboxService;
    private final Clock clock;

    /**
     * @param previousAvailable available quantity before the change
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void stockChanged(InventoryItem item, int previousAvailable) {
        if (item.availableQuantity() == previousAvailable) {
            return;
        }
        outboxService.enqueue(MessagingConstants.INVENTORY_EXCHANGE, MessagingConstants.STOCK_LEVEL_CHANGED_KEY,
                new StockLevelChangedEvent(UUID.randomUUID(), clock.instant(), item.getProductId(),
                        item.availableQuantity()),
                item.getProductId());
    }
}
