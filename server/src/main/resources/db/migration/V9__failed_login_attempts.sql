-- Failed login attempts table for sliding-window lockout enforcement.
-- Tracks failed login attempts by username and IP address within a configurable time window.
CREATE TABLE failed_login_attempts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(255) NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    device_id VARCHAR(36) NOT NULL,
    attempt_timestamp BIGINT NOT NULL
);

CREATE INDEX failed_login_attempts_username_idx ON failed_login_attempts (username);
CREATE INDEX failed_login_attempts_ip_address_idx ON failed_login_attempts (ip_address);
CREATE INDEX failed_login_attempts_attempt_timestamp_idx ON failed_login_attempts (attempt_timestamp);

