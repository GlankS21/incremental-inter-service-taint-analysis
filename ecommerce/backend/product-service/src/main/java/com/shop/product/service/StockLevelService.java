package com.shop.product.service;

import com.shop.common.event.StockLevelChangedEvent;

/**
 * "In stock" flag of the products, from the stock levels published by inventory-service.
 */
public interface StockLevelService {

    /**
     * @return whether the flag was updated (false for an unknown product or an outdated event)
     */
    boolean applyStockLevel(StockLevelChangedEvent event);
}
