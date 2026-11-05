package com.shop.product.repository;

import com.shop.product.entity.ProductPurchase;
import com.shop.product.entity.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductPurchaseRepository extends JpaRepository<ProductPurchase, Long> {

    boolean existsByUserIdAndProductId(UUID userId, Long productId);

    Optional<ProductPurchase> findFirstByUserIdAndProductIdOrderByPurchasedAtDesc(UUID userId, Long productId);

    long countByOrderId(UUID orderId);

    /**
     * Idempotent insert: a redelivered {@code order.delivered} hits the unique key and is skipped.
     *
     * @return 1 when the row was inserted, 0 when it already existed
     */
    @Modifying
    @Query(value = """
            insert into product_purchases (user_id, product_id, order_id, order_number, purchased_at)
            values (:userId, :productId, :orderId, :orderNumber, :purchasedAt)
            on conflict (user_id, product_id, order_id) do nothing
            """, nativeQuery = true)
    int insertIgnoringDuplicates(@Param("userId") UUID userId,
                                 @Param("productId") Long productId,
                                 @Param("orderId") UUID orderId,
                                 @Param("orderNumber") String orderNumber,
                                 @Param("purchasedAt") Instant purchasedAt);

    /**
     * Products the customer received and has not reviewed, newest delivery first. A product received in
     * several orders appears once, with its latest recorded delivery. Products that are no longer in the
     * given status (archived, or deleted with their purchases) are left out: they cannot be reviewed.
     */
    @Query(value = """
            select pp from ProductPurchase pp
            where pp.id in (select max(latest.id) from ProductPurchase latest
                            where latest.userId = :userId group by latest.productId)
              and exists (select 1 from Product p where p.id = pp.productId and p.status = :status)
              and not exists (select 1 from ProductReview r where r.product.id = pp.productId and r.userId = :userId)
            order by pp.purchasedAt desc, pp.id desc
            """,
            countQuery = """
                    select count(distinct pp.productId) from ProductPurchase pp
                    where pp.userId = :userId
                      and exists (select 1 from Product p where p.id = pp.productId and p.status = :status)
                      and not exists (select 1 from ProductReview r
                                      where r.product.id = pp.productId and r.userId = :userId)
                    """)
    Page<ProductPurchase> findPendingReviews(@Param("userId") UUID userId,
                                             @Param("status") ProductStatus status,
                                             Pageable pageable);

    /**
     * Number of products of each given order that the customer has not reviewed yet (orders without such
     * products are absent).
     */
    @Query("""
            select pp.orderId as orderId, count(distinct pp.productId) as pending from ProductPurchase pp
            where pp.userId = :userId and pp.orderId in :orderIds
              and exists (select 1 from Product p where p.id = pp.productId and p.status = :status)
              and not exists (select 1 from ProductReview r where r.product.id = pp.productId and r.userId = :userId)
            group by pp.orderId
            """)
    List<OrderPendingReviews> countPendingReviewsByOrder(@Param("userId") UUID userId,
                                                         @Param("orderIds") Collection<UUID> orderIds,
                                                         @Param("status") ProductStatus status);

    @Query("select distinct pp.productId from ProductPurchase pp where pp.userId = :userId and pp.productId in :productIds")
    List<Long> findPurchasedProductIds(@Param("userId") UUID userId, @Param("productIds") Collection<Long> productIds);

    /**
     * Orders delivered at or before {@code deliveredBefore} that the reminder job has not handled yet.
     */
    @Query("""
            select distinct pp.orderId from ProductPurchase pp
            where pp.reviewReminderProcessedAt is null and pp.purchasedAt <= :deliveredBefore
            """)
    List<UUID> findOrdersDueForReminder(@Param("deliveredBefore") Instant deliveredBefore, Pageable pageable);

    /**
     * Locks the rows of an order still to be handled by the reminder job; rows locked by another instance
     * are skipped, so two instances never remind the same order.
     */
    @Query(value = """
            select * from product_purchases
            where order_id = :orderId and review_reminder_processed_at is null
            order by id
            for update skip locked
            """, nativeQuery = true)
    List<ProductPurchase> lockUnremindedByOrderId(@Param("orderId") UUID orderId);

    @Modifying
    @Query("update ProductPurchase pp set pp.reviewReminderProcessedAt = :at where pp.id in :ids")
    int markReminderProcessed(@Param("ids") Collection<Long> ids, @Param("at") Instant at);

    /**
     * Pending reviews of one order.
     */
    interface OrderPendingReviews {

        UUID getOrderId();

        long getPending();
    }
}
