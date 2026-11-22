package com.shop.auth.service.impl;

import com.shop.auth.config.AuthProperties;
import com.shop.auth.dto.request.ForgotPasswordRequest;
import com.shop.auth.dto.request.ResetPasswordRequest;
import com.shop.auth.entity.PasswordResetToken;
import com.shop.auth.entity.UserAccount;
import com.shop.auth.repository.PasswordResetTokenRepository;
import com.shop.auth.repository.UserAccountRepository;
import com.shop.auth.security.OpaqueTokenGenerator;
import com.shop.auth.service.PasswordResetService;
import com.shop.auth.service.TokenService;
import com.shop.auth.util.EmailUtils;
import com.shop.auth.util.TokenHashUtils;
import com.shop.common.event.PasswordResetRequestedEvent;
import com.shop.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {

    static final String INVALID_TOKEN = "auth.error.reset-token-invalid";

    private final UserAccountRepository userAccountRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final OpaqueTokenGenerator opaqueTokenGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthProperties authProperties;
    private final Clock clock;

    @Override
    @Transactional
    public void requestReset(ForgotPasswordRequest request) {
        // The account row is locked so that concurrent requests cannot both pass the cooldown check.
        UserAccount account = userAccountRepository.findByEmailForUpdate(EmailUtils.normalize(request.email()))
                .orElse(null);
        if (account == null) {
            log.debug("Password reset requested for an unknown e-mail, ignored");
            return;
        }
        if (!account.isEnabled()) {
            log.info("Password reset requested for disabled account {}, ignored", account.getId());
            return;
        }
        Instant now = clock.instant();
        if (passwordResetTokenRepository.existsByUserAccountIdAndCreatedAtAfter(account.getId(),
                now.minus(authProperties.passwordResetCooldown()))) {
            log.info("Password reset for account {} requested again within the cooldown, ignored", account.getId());
            return;
        }

        // Only the latest link works.
        passwordResetTokenRepository.invalidateAllUnusedByUserAccountId(account.getId(), now);
        String rawToken = opaqueTokenGenerator.generate();
        PasswordResetToken token = passwordResetTokenRepository.save(PasswordResetToken.builder()
                .userAccount(account)
                .tokenHash(TokenHashUtils.sha256Hex(rawToken))
                .createdAt(now)
                .expiresAt(now.plus(authProperties.passwordResetTtl()))
                .build());

        // Relayed to RabbitMQ only after the transaction commits (see UserEventPublisher).
        eventPublisher.publishEvent(new PasswordResetRequestedEvent(
                UUID.randomUUID(), now, account.getId(), account.getEmail(), rawToken, token.getExpiresAt()));
        log.info("Password reset requested for account {}", account.getId());
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        Instant now = clock.instant();
        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenHashForUpdate(TokenHashUtils.sha256Hex(request.token()))
                .filter(candidate -> candidate.isUsable(now))
                .orElseThrow(() -> new BadRequestException(INVALID_TOKEN));
        UserAccount account = token.getUserAccount();
        if (!account.isEnabled()) {
            throw new BadRequestException(INVALID_TOKEN);
        }

        account.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        token.markUsed(now);
        passwordResetTokenRepository.invalidateAllUnusedByUserAccountId(account.getId(), now);
        // Whoever knew the old password must re-authenticate.
        tokenService.revokeAll(account.getId());
        log.info("Password reset completed for account {}", account.getId());
    }

    /**
     * Used tokens are kept for the duration of the cooldown, so that a reset does not allow an
     * immediate new request.
     */
    @Override
    @Transactional
    public int purgeObsolete() {
        Instant now = clock.instant();
        return passwordResetTokenRepository.deleteExpiredOrUsedBefore(now,
                now.minus(authProperties.passwordResetCooldown()));
    }
}
