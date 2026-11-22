package com.shop.inventory.messaging.outbox;

import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.StockLevelChangedEvent;
import com.shop.inventory.entity.InventoryItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class StockLevelNotifierTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    @Mock
    private OutboxService outboxService;

    private StockLevelNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new StockLevelNotifier(outboxService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publishesTheNewAvailableQuantity() {
        InventoryItem item = item(7L, 10, 4);

        notifier.stockChanged(item, 10);

        ArgumentCaptor<StockLevelChangedEvent> event = ArgumentCaptor.forClass(StockLevelChangedEvent.class);
        verify(outboxService).enqueue(eq(MessagingConstants.INVENTORY_EXCHANGE),
                eq(MessagingConstants.STOCK_LEVEL_CHANGED_KEY), event.capture(), eq(7L));
        assertThat(event.getValue().productId()).isEqualTo(7L);
        assertThat(event.getValue().availableQuantity()).isEqualTo(6);
        assertThat(event.getValue().occurredAt()).isEqualTo(NOW);
    }

    @Test
    void staysSilentWhenTheAvailableQuantityDidNotChange() {
        // A sale commit lowers on-hand and reserved units together
        notifier.stockChanged(item(7L, 8, 2), 6);

        verifyNoInteractions(outboxService);
    }

    @Test
    void publishesWhenTheProductRunsOut() {
        notifier.stockChanged(item(3L, 2, 2), 2);

        ArgumentCaptor<StockLevelChangedEvent> event = ArgumentCaptor.forClass(StockLevelChangedEvent.class);
        verify(outboxService).enqueue(anyString(), anyString(), event.capture(), any());
        assertThat(event.getValue().availableQuantity()).isZero();
    }

    private InventoryItem item(Long productId, int onHand, int reserved) {
        return InventoryItem.builder()
                .productId(productId)
                .quantityOnHand(onHand)
                .quantityReserved(reserved)
                .lowStockThreshold(1)
                .build();
    }
}
