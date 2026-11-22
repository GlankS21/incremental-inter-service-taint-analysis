package com.shop.product.service.impl;

import com.shop.common.event.StockLevelChangedEvent;
import com.shop.product.repository.ProductRepository;
import com.shop.product.service.SearchIndexMaintenance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockLevelServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final Instant AT = Instant.parse("2026-10-27T09:59:00Z");

    @Mock
    private ProductRepository productRepository;
    @Mock
    private SearchIndexMaintenance searchIndexMaintenance;

    private StockLevelServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StockLevelServiceImpl(productRepository, searchIndexMaintenance, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void zeroAvailableMeansOutOfStock() {
        when(productRepository.updateStock(7L, false, AT)).thenReturn(1);

        assertThat(service.applyStockLevel(new StockLevelChangedEvent(UUID.randomUUID(), AT, 7L, 0))).isTrue();
        verify(searchIndexMaintenance).catalogChanged();
    }

    @Test
    void outdatedLevelChangesNothing() {
        when(productRepository.updateStock(7L, true, AT)).thenReturn(0);

        assertThat(service.applyStockLevel(new StockLevelChangedEvent(UUID.randomUUID(), AT, 7L, 4))).isFalse();
        verifyNoInteractions(searchIndexMaintenance);
    }

    @Test
    void missingTimeFallsBackToTheClock() {
        when(productRepository.updateStock(7L, true, NOW)).thenReturn(1);

        assertThat(service.applyStockLevel(new StockLevelChangedEvent(UUID.randomUUID(), null, 7L, 2))).isTrue();
    }

    @Test
    void eventWithoutProductIsIgnored() {
        assertThat(service.applyStockLevel(new StockLevelChangedEvent(UUID.randomUUID(), AT, null, 2))).isFalse();
        verify(productRepository, never()).updateStock(anyLong(), anyBoolean(), any());
    }
}
