package com.shop.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A product received by a customer in a DELIVERED order: the proof of purchase required to review it.
 * Rows are written by the {@code order.delivered} consumer with a duplicate-ignoring insert
 * (see {@code ProductPurchaseRepository#insertIgnoringDuplicates}).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "product_purchases")
public class ProductPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    /** {@code null} for deliveries recorded before the order number was kept. */
    @Column(name = "order_number", length = 40, updatable = false)
    private String orderNumber;

    @Column(name = "purchased_at", nullable = false, updatable = false)
    private Instant purchasedAt;

    /** When the review reminder job handled the order of this row; it is never reminded again. */
    @Column(name = "review_reminder_processed_at")
    private Instant reviewReminderProcessedAt;
}
