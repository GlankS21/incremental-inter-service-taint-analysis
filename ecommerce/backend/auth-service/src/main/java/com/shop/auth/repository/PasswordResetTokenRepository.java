package com.shop.auth.repository;

import com.shop.auth.entity.PasswordResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /**
     * Row lock so that two concurrent resets with the same token cannot both succeed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * Whether a token was issued for the account after {@code after} (request cooldown).
     */
    boolean existsByUserAccountIdAndCreatedAtAfter(UUID userAccountId, Instant after);

    /**
     * Makes every still unused token of the account unusable.
     */
    @Modifying(flushAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now "
            + "where t.userAccount.id = :userAccountId and t.usedAt is null")
    int invalidateAllUnusedByUserAccountId(@Param("userAccountId") UUID userAccountId, @Param("now") Instant now);

    /**
     * Deletes tokens that expired before {@code now} or were used before {@code usedBefore}.
     */
    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :now or t.usedAt < :usedBefore")
    int deleteExpiredOrUsedBefore(@Param("now") Instant now, @Param("usedBefore") Instant usedBefore);

    long countByUserAccountIdAndUsedAtIsNull(UUID userAccountId);
}
