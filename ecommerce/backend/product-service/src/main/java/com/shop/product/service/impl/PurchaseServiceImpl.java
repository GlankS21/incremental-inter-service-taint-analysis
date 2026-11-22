package com.shop.product.service.impl;

import com.shop.common.event.OrderDeliveredEvent;
import com.shop.common.event.OrderItemPayload;
import com.shop.product.repository.ProductPurchaseRepository;
import com.shop.product.repository.ProductRepository;
import com.shop.product.service.PurchaseService;
import com.shop.product.service.SearchIndexMaintenance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseServiceImpl implements PurchaseService {

    private final ProductPurchaseRepository purchaseRepository;
    private final ProductRepository productRepository;
    private final SearchIndexMaintenance searchIndexMaintenance;
    private final Clock clock;

    @Override
    @Transactional
    public int recordDeliveredOrder(OrderDeliveredEvent event) {
        if (event.userId() == null || event.orderId() == null || event.items() == null) {
            log.warn("Ignoring incomplete order.delivered event {}", event.eventId());
            return 0;
        }
        List<Long> productIds = event.items().stream()
                .filter(Objects::nonNull)
                .map(OrderItemPayload::productId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return 0;
        }

        Set<Long> knownIds = new HashSet<>(productRepository.findExistingIds(productIds));
        Instant purchasedAt = event.occurredAt() != null ? event.occurredAt() : clock.instant();
        int inserted = 0;
        for (Long productId : productIds) {
            if (!knownIds.contains(productId)) {
                log.warn("Order {} contains unknown product {}; no purchase recorded", event.orderId(), productId);
                continue;
            }
            if (purchaseRepository.insertIgnoringDuplicates(
                    event.userId(), productId, event.orderId(), event.orderNumber(), purchasedAt) == 1) {
                productRepository.addSales(productId, 1);
                inserted++;
            }
        }
        if (inserted > 0) {
            searchIndexMaintenance.catalogChanged();
        }
        log.info("Order {} delivered to user {}: {} new purchase record(s) for {} product(s)",
                event.orderId(), event.userId(), inserted, productIds.size());
        return inserted;
    }
}
