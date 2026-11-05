package com.shop.notification.repository;

import com.shop.notification.entity.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    /**
     * Creates or replaces the settings of a customer in one statement, so that two concurrent first saves
     * do not collide on the primary key.
     */
    @Modifying
    @Query(value = """
            insert into notification_preferences (user_id, review_reminders, updated_at)
            values (:userId, :reviewReminders, :at)
            on conflict (user_id) do update
                set review_reminders = excluded.review_reminders, updated_at = excluded.updated_at
            """, nativeQuery = true)
    int upsert(@Param("userId") UUID userId, @Param("reviewReminders") boolean reviewReminders,
               @Param("at") Instant at);
}
