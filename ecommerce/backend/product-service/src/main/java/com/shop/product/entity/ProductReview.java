package com.shop.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Review of a product by a customer who received it; at most one per customer and product.
 * The author name is a display name chosen by the customer, stored as written.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "product_reviews")
public class ProductReview extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "author_name", nullable = false, length = 60)
    private String authorName;

    /** 1 to 5 stars. */
    @Column(nullable = false)
    private short rating;

    @Column(length = 2000)
    private String comment;

    /** The author received the product in a delivered order when writing the review. */
    @Column(name = "verified_purchase", nullable = false)
    private boolean verifiedPurchase;

    @Version
    private Long version;
}
