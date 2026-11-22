-- Single-use tokens of the "forgot password" flow. Only the SHA-256 hash of a token is stored.
-- used_at is set when the token resets the password or is superseded by a newer token.

CREATE TABLE password_reset_tokens
(
    id              UUID PRIMARY KEY,
    user_account_id UUID        NOT NULL,
    token_hash      VARCHAR(64) NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    CONSTRAINT uk_password_reset_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_tokens_account FOREIGN KEY (user_account_id)
        REFERENCES user_accounts (id) ON DELETE CASCADE
);

-- Cooldown check (latest token of an account) and invalidation of an account's tokens
CREATE INDEX idx_password_reset_tokens_account_created ON password_reset_tokens (user_account_id, created_at DESC);
-- Cleanup job
CREATE INDEX idx_password_reset_tokens_expires_at ON password_reset_tokens (expires_at);
