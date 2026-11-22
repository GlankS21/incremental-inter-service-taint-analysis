package com.shop.auth.scheduler;

import com.shop.auth.service.PasswordResetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes expired and used password reset tokens. Safe to run on several instances at once
 * (idempotent delete).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetTokenCleanupJob {

    private final PasswordResetService passwordResetService;

    @Scheduled(cron = "${app.auth.password-reset-token-cleanup-cron:0 30 3 * * *}")
    public void purgeObsoleteTokens() {
        int deleted = passwordResetService.purgeObsolete();
        log.info("Password reset token cleanup removed {} expired or used token(s)", deleted);
    }
}
