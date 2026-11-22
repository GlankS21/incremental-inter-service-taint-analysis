package com.shop.auth.service;

import com.shop.auth.dto.request.ForgotPasswordRequest;
import com.shop.auth.dto.request.ResetPasswordRequest;

/**
 * "Forgot password" flow: a single-use link is e-mailed (by notification-service), then exchanged
 * for a new password.
 */
public interface PasswordResetService {

    /**
     * Issues a reset token and publishes it for e-mailing when the account exists, is enabled and no
     * token was issued within the cooldown. Never reveals whether the account exists: unknown,
     * disabled and throttled requests complete silently.
     */
    void requestReset(ForgotPasswordRequest request);

    /**
     * Sets the new password, consumes the token, invalidates the other reset tokens of the account
     * and revokes every session of the account.
     *
     * @throws com.shop.common.exception.BadRequestException when the token is unknown, used or expired,
     *                                                       or the account is disabled
     */
    void resetPassword(ResetPasswordRequest request);

    /**
     * Deletes expired and used reset tokens and returns how many were removed.
     */
    int purgeObsolete();
}
