-- Trusted devices table for device-based authentication trust.
-- Each row tracks one user/device pair. The deviceId is a client-side UUID that persists
-- across logins, enabling device-based trust rather than IP-based trust.
-- The IP address is stored for context in security alerts but is not the primary key.
CREATE TABLE user_trusted_devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(36) NOT NULL,
    last_ip_address VARCHAR(45),
    first_seen_at BIGINT NOT NULL,
    last_used_at BIGINT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX user_trusted_devices_user_id_device_id_unique
    ON user_trusted_devices (user_id, device_id);
