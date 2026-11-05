package com.shop.user.repository;

import com.shop.user.entity.UserProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    /**
     * Profiles whose e-mail, first or last name contains the keyword, ignoring case. The keyword is a LIKE
     * fragment with {@code \} as escape character: {@code %}, {@code _} and {@code \} must be escaped.
     */
    @Query("""
            select p from UserProfile p
            where lower(p.email) like lower(concat('%', :keyword, '%')) escape '\\'
               or lower(p.firstName) like lower(concat('%', :keyword, '%')) escape '\\'
               or lower(p.lastName) like lower(concat('%', :keyword, '%')) escape '\\'
            """)
    Page<UserProfile> search(@Param("keyword") String keyword, Pageable pageable);

    /**
     * Creates the profile unless it already exists; a concurrent creation of the same profile is not an
     * error. Returns the number of rows inserted.
     */
    @Modifying
    @Query(value = """
            insert into user_profiles (id, email, created_at, updated_at, version)
            values (:id, :email, :now, :now, 0)
            on conflict (id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("email") String email, @Param("now") Instant now);

    /**
     * Locks the profile row: changes of the address book of a user are serialized on it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserProfile p where p.id = :id")
    Optional<UserProfile> findForUpdateById(@Param("id") UUID id);
}
