package com.shop.product.repository;

import com.shop.product.entity.ProductReview;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filters of the admin review listing. Each factory returns {@code null} when its filter is not set,
 * which {@link Specification#allOf} ignores.
 */
public final class ProductReviewSpecifications {

    private ProductReviewSpecifications() {
    }

    public static Specification<ProductReview> ofProduct(Long productId) {
        return productId == null ? null : (root, query, cb) -> cb.equal(root.get("product").get("id"), productId);
    }

    public static Specification<ProductReview> hasRating(Integer rating) {
        return rating == null ? null : (root, query, cb) -> cb.equal(root.get("rating"), rating.shortValue());
    }
}
