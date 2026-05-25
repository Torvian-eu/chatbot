-- Device registry table for device-aware user preferences.
-- Each row tracks one user/device pair by the client-side UUID and stores usage timestamps.
CREATE TABLE user_devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    client_device_id VARCHAR(36) NOT NULL,
    device_name VARCHAR(255),
    created_at BIGINT NOT NULL,
    last_used_at BIGINT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX user_devices_user_id_client_device_id_unique
    ON user_devices (user_id, client_device_id);

-- User preferences table with global and device-specific scopes.
-- scope_id stores the scope identifier: "GLOBAL" for global settings, or the clientDeviceId (UUID) for device-specific settings.
-- device_id remains as a nullable FK for relational integrity with user_devices.
CREATE TABLE user_preferences (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    device_id BIGINT,
    scope_id VARCHAR(36) NOT NULL,
    pref_key VARCHAR(255) NOT NULL,
    pref_value TEXT NOT NULL,
    updated_at BIGINT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (device_id) REFERENCES user_devices (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX user_preferences_user_id_pref_key_scope_id_unique
    ON user_preferences (user_id, pref_key, scope_id);
