package com.shop.auth.service.impl;

import com.shop.auth.config.AuthProperties;
import com.shop.auth.dto.request.ForgotPasswordRequest;
import com.shop.auth.dto.request.ResetPasswordRequest;
import com.shop.auth.entity.PasswordResetToken;
import com.shop.auth.entity.UserAccount;
import com.shop.auth.repository.PasswordResetTokenRepository;
import com.shop.auth.repository.UserAccountRepository;
import com.shop.auth.security.OpaqueTokenGenerator;
import com.shop.auth.service.TokenService;
import com.shop.auth.util.TokenHashUtils;
import com.shop.common.event.PasswordResetRequestedEvent;
import com.shop.common.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final String RAW_TOKEN = "raw-reset-token";

    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private TokenService tokenService;
    @Mock
    private OpaqueTokenGenerator opaqueTokenGenerator;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final AuthProperties authProperties = new AuthProperties(Duration.ofMinutes(15), Duration.ofDays(7),
            Duration.ofMinutes(30), Duration.ofMinutes(1));

    private PasswordResetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetServiceImpl(userAccountRepository, passwordResetTokenRepository, tokenService,
                passwordEncoder, opaqueTokenGenerator, eventPublisher, authProperties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void requestReset_issuesTokenStoresOnlyItsHashAndPublishesEvent() {
        UserAccount account = account(true);
        when(userAccountRepository.findByEmailForUpdate("john@example.com")).thenReturn(Optional.of(account));
        when(passwordResetTokenRepository.existsByUserAccountIdAndCreatedAtAfter(account.getId(),
                NOW.minus(Duration.ofMinutes(1)))).thenReturn(false);
        when(opaqueTokenGenerator.generate()).thenReturn(RAW_TOKEN);
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.requestReset(new ForgotPasswordRequest("  John@Example.COM "));

        verify(passwordResetTokenRepository).invalidateAllUnusedByUserAccountId(account.getId(), NOW);
        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(saved.capture());
        assertThat(saved.getValue().getTokenHash()).isEqualTo(TokenHashUtils.sha256Hex(RAW_TOKEN));
        assertThat(saved.getValue().getUserAccount()).isSameAs(account);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(saved.getValue().isUsed()).isFalse();

        ArgumentCaptor<PasswordResetRequestedEvent> event = ArgumentCaptor.forClass(PasswordResetRequestedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(account.getId());
        assertThat(event.getValue().email()).isEqualTo("john@example.com");
        assertThat(event.getValue().token()).isEqualTo(RAW_TOKEN);
        assertThat(event.getValue().occurredAt()).isEqualTo(NOW);
        assertThat(event.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    void requestReset_withUnknownEmailDoesNothing() {
        when(userAccountRepository.findByEmailForUpdate("ghost@example.com")).thenReturn(Optional.empty());

        service.requestReset(new ForgotPasswordRequest("ghost@example.com"));

        verify(passwordResetTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void requestReset_forDisabledAccountDoesNothing() {
        when(userAccountRepository.findByEmailForUpdate("john@example.com")).thenReturn(Optional.of(account(false)));

        service.requestReset(new ForgotPasswordRequest("john@example.com"));

        verify(passwordResetTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void requestReset_withinCooldownDoesNothing() {
        UserAccount account = account(true);
        when(userAccountRepository.findByEmailForUpdate("john@example.com")).thenReturn(Optional.of(account));
        when(passwordResetTokenRepository.existsByUserAccountIdAndCreatedAtAfter(account.getId(),
                NOW.minus(Duration.ofMinutes(1)))).thenReturn(true);

        service.requestReset(new ForgotPasswordRequest("john@example.com"));

        verify(passwordResetTokenRepository, never()).invalidateAllUnusedByUserAccountId(any(), any());
        verify(passwordResetTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void resetPassword_setsPasswordConsumesTokenAndRevokesAllSessions() {
        UserAccount account = account(true);
        PasswordResetToken token = token(account, NOW.plus(Duration.ofMinutes(10)), null);
        when(passwordResetTokenRepository.findByTokenHashForUpdate(TokenHashUtils.sha256Hex(RAW_TOKEN)))
                .thenReturn(Optional.of(token));

        service.resetPassword(new ResetPasswordRequest(RAW_TOKEN, "NewSecret456"));

        assertThat(passwordEncoder.matches("NewSecret456", account.getPasswordHash())).isTrue();
        assertThat(token.getUsedAt()).isEqualTo(NOW);
        verify(passwordResetTokenRepository).invalidateAllUnusedByUserAccountId(account.getId(), NOW);
        verify(tokenService).revokeAll(account.getId());
    }

    @Test
    void resetPassword_withExpiredTokenFails() {
        UserAccount account = account(true);
        when(passwordResetTokenRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(token(account, NOW, null)));

        assertRejected(account);
    }

    @Test
    void resetPassword_withUsedTokenFails() {
        UserAccount account = account(true);
        when(passwordResetTokenRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(token(account, NOW.plus(Duration.ofMinutes(10)), NOW.minusSeconds(5))));

        assertRejected(account);
    }

    @Test
    void resetPassword_withUnknownTokenFails() {
        when(passwordResetTokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest("unknown", "NewSecret456")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(PasswordResetServiceImpl.INVALID_TOKEN);
        verify(tokenService, never()).revokeAll(any());
    }

    @Test
    void resetPassword_forDisabledAccountFails() {
        UserAccount account = account(false);
        when(passwordResetTokenRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(token(account, NOW.plus(Duration.ofMinutes(10)), null)));

        assertRejected(account);
    }

    @Test
    void purgeObsolete_keepsUsedTokensForTheCooldown() {
        when(passwordResetTokenRepository.deleteExpiredOrUsedBefore(NOW, NOW.minus(Duration.ofMinutes(1))))
                .thenReturn(3);

        assertThat(service.purgeObsolete()).isEqualTo(3);
    }

    private void assertRejected(UserAccount account) {
        String previousHash = account.getPasswordHash();
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(RAW_TOKEN, "NewSecret456")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(PasswordResetServiceImpl.INVALID_TOKEN);
        assertThat(account.getPasswordHash()).isEqualTo(previousHash);
        verify(tokenService, never()).revokeAll(any());
    }

    private UserAccount account(boolean enabled) {
        return UserAccount.builder()
                .id(UUID.randomUUID())
                .email("john@example.com")
                .passwordHash(passwordEncoder.encode("Secret123"))
                .enabled(enabled)
                .build();
    }

    private PasswordResetToken token(UserAccount account, Instant expiresAt, Instant usedAt) {
        return PasswordResetToken.builder()
                .id(UUID.randomUUID())
                .userAccount(account)
                .tokenHash(TokenHashUtils.sha256Hex(RAW_TOKEN))
                .createdAt(NOW.minus(Duration.ofMinutes(20)))
                .expiresAt(expiresAt)
                .usedAt(usedAt)
                .build();
    }
}
