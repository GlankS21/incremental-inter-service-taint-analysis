package com.shop.product.service.impl;

import com.shop.common.event.OrderDeliveredEvent;
import com.shop.common.event.OrderItemPayload;
import com.shop.product.repository.ProductPurchaseRepository;
import com.shop.product.repository.ProductRepository;
import com.shop.product.service.SearchIndexMaintenance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
    private static final Instant DELIVERED_AT = Instant.parse("2026-10-20T08:30:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final String ORDER_NUMBER = "ORD-20261020-0001";

    @Mock
    private ProductPurchaseRepository purchaseRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private SearchIndexMaintenance searchIndexMaintenance;

    private PurchaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(purchaseRepository, productRepository, searchIndexMaintenance,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordsKnownProductsOnceAndIgnoresUnknownOnes() {
        when(productRepository.findExistingIds(List.of(1L, 2L, 999L))).thenReturn(List.of(1L, 2L));
        when(purchaseRepository.insertIgnoringDuplicates(USER_ID, 1L, ORDER_ID, ORDER_NUMBER, DELIVERED_AT)).thenReturn(1);
        when(purchaseRepository.insertIgnoringDuplicates(USER_ID, 2L, ORDER_ID, ORDER_NUMBER, DELIVERED_AT)).thenReturn(1);

        // Product 1 appears twice (two lines of the same product)
        int inserted = service.recordDeliveredOrder(event(DELIVERED_AT, item(1L), item(2L), item(1L), item(999L)));

        assertThat(inserted).isEqualTo(2);
        verify(purchaseRepository, never()).insertIgnoringDuplicates(any(), eq(999L), any(), any(), any());
        // Each new purchase counts as a sale for the search ranking
        verify(productRepository).addSales(1L, 1);
        verify(productRepository).addSales(2L, 1);
        verify(searchIndexMaintenance).catalogChanged();
    }

    @Test
    void redeliveredEventInsertsNothing() {
        when(productRepository.findExistingIds(List.of(1L))).thenReturn(List.of(1L));
        // The unique key (user, product, order) already holds the row
        when(purchaseRepository.insertIgnoringDuplicates(USER_ID, 1L, ORDER_ID, ORDER_NUMBER, DELIVERED_AT)).thenReturn(0);

        assertThat(service.recordDeliveredOrder(event(DELIVERED_AT, item(1L)))).isZero();
        verify(productRepository, never()).addSales(anyLong(), anyInt());
        verifyNoInteractions(searchIndexMaintenance);
    }

    @Test
    void missingOccurredAtFallsBackToTheClock() {
        when(productRepository.findExistingIds(List.of(3L))).thenReturn(List.of(3L));
        when(purchaseRepository.insertIgnoringDuplicates(USER_ID, 3L, ORDER_ID, ORDER_NUMBER, NOW)).thenReturn(1);

        assertThat(service.recordDeliveredOrder(event(null, item(3L)))).isEqualTo(1);
    }

    @Test
    void eventWithoutUserOrProductsIsIgnored() {
        OrderDeliveredEvent withoutUser = new OrderDeliveredEvent(UUID.randomUUID(), NOW, ORDER_ID, "ORD-1", null, null,
                List.of(item(1L)));
        assertThat(service.recordDeliveredOrder(withoutUser)).isZero();
        assertThat(service.recordDeliveredOrder(event(NOW, item(null)))).isZero();

        verifyNoInteractions(productRepository);
        verify(purchaseRepository, never()).insertIgnoringDuplicates(any(), anyLong(), any(), any(), any());
    }

    private static OrderDeliveredEvent event(Instant occurredAt, OrderItemPayload... items) {
        return new OrderDeliveredEvent(UUID.randomUUID(), occurredAt, ORDER_ID, ORDER_NUMBER, USER_ID,
                "buyer@example.com", List.of(items));
    }

    private static OrderItemPayload item(Long productId) {
        return new OrderItemPayload(productId, "SKU-" + productId, "Product " + productId, 1, new BigDecimal("1000"));
    }
}
