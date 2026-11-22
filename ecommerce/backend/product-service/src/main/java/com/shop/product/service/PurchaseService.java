package com.shop.product.service;

import com.shop.common.event.OrderDeliveredEvent;

/**
 * Proofs of purchase, which entitle a customer to review a product.
 */
public interface PurchaseService {

    /**
     * Records every known product of a delivered order for its customer. Idempotent: a redelivered
     * event adds nothing; unknown product ids are ignored.
     *
     * @return the number of new purchase records
     */
    int recordDeliveredOrder(OrderDeliveredEvent event);
}
