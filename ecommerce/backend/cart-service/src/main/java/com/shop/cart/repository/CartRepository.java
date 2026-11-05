package com.shop.cart.repository;

import com.shop.cart.entity.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<Cart> findByUserId(UUID userId);

    /**
     * Serialises concurrent changes of one cart (two browser tabs, checkout clearing the cart).
     * Items load lazily afterwards: PostgreSQL cannot lock the nullable side of an outer join.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.userId = :userId")
    Optional<Cart> findByUserIdForUpdate(@Param("userId") UUID userId);

    /**
     * Creates the empty cart of a customer unless a concurrent request already did: two first additions
     * at the same time would otherwise both insert and one would fail on {@code uk_carts_user}.
     */
    @Modifying
    @Query(value = """
            insert into carts (id, user_id, created_at, updated_at, version)
            values (:id, :userId, now(), now(), 0)
            on conflict (user_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * Records a handled event.
     *
     * @return 1 the first time, 0 when the event was already handled (redelivery)
     */
    @Modifying
    @Query(value = """
            insert into processed_events (event_id, processed_at)
            values (:eventId, now())
            on conflict (event_id) do nothing
            """, nativeQuery = true)
    int markEventProcessed(@Param("eventId") UUID eventId);
}
