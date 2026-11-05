package com.shop.product.repository;

import com.shop.product.entity.ProductReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductReviewRepository extends JpaRepository<ProductReview, Long>,
        JpaSpecificationExecutor<ProductReview> {

    Page<ProductReview> findAllByProductId(Long productId, Pageable pageable);

    Optional<ProductReview> findByProductIdAndUserId(Long productId, UUID userId);

    /**
     * Admin listing: the product is fetched with the page for its name and slug.
     */
    @Override
    @EntityGraph(attributePaths = "product")
    Page<ProductReview> findAll(Specification<ProductReview> specification, Pageable pageable);

    /**
     * The reviews written by a customer, with their product (name, slug, status; images batch-fetched).
     */
    @EntityGraph(attributePaths = "product")
    Page<ProductReview> findAllByUserId(UUID userId, Pageable pageable);

    @Query("select r from ProductReview r where r.userId = :userId and r.product.id in :productIds")
    List<ProductReview> findAllOfUserForProducts(@Param("userId") UUID userId,
                                                 @Param("productIds") Collection<Long> productIds);

    long countByProductId(Long productId);

    /**
     * @return the sum of the ratings of a product, {@code null} when it has no review
     */
    @Query("select sum(r.rating) from ProductReview r where r.product.id = :productId")
    Long sumRatingsByProductId(@Param("productId") Long productId);
}
