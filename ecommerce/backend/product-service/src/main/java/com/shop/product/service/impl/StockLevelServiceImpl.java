package com.shop.product.service.impl;

import com.shop.common.event.StockLevelChangedEvent;
import com.shop.product.repository.ProductRepository;
import com.shop.product.service.SearchIndexMaintenance;
import com.shop.product.service.StockLevelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockLevelServiceImpl implements StockLevelService {

    private final ProductRepository productRepository;
    private final SearchIndexMaintenance searchIndexMaintenance;
    private final Clock clock;

    @Override
    @Transactional
    public boolean applyStockLevel(StockLevelChangedEvent event) {
        if (event.productId() == null) {
            log.warn("Ignoring stock level event {} without product", event.eventId());
            return false;
        }
        Instant at = event.occurredAt() != null ? event.occurredAt() : clock.instant();
        boolean inStock = event.availableQuantity() > 0;
        if (productRepository.updateStock(event.productId(), inStock, at) == 0) {
            log.debug("Stock level of product {} at {} ignored (unknown product or newer level applied)",
                    event.productId(), at);
            return false;
        }
        searchIndexMaintenance.catalogChanged();
        log.debug("Product {} is {}", event.productId(), inStock ? "in stock" : "out of stock");
        return true;
    }
}
