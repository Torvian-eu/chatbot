-- Device verification tokens for email-based device trust
-- Allows users to promote unrecognized devices to "Trusted" via email verification link

CREATE TABLE IF NOT EXISTS device_verification_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id VARCHAR(36) NOT NULL,
    token VARCHAR(36) NOT NULL UNIQUE,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

-- Index for efficient token lookup
CREATE INDEX IF NOT EXISTS device_verification_tokens_token_idx ON device_verification_tokens(token);

-- Index for rate limiting queries (user_id + device_id)
CREATE INDEX IF NOT EXISTS device_verification_tokens_user_device_idx ON device_verification_tokens(user_id, device_id);
