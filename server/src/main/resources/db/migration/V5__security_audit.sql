-- Security audit table for tracking unacknowledged login attempts from unrecognized devices.
-- When a user acknowledges these alerts, the device is promoted to the trusted devices table.
CREATE TABLE security_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(36) NOT NULL,
    ip_address VARCHAR(45),
    created_at BIGINT NOT NULL,
    is_acknowledged BOOLEAN NOT NULL DEFAULT 0,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX security_audit_user_id_idx ON security_audit (user_id);
CREATE INDEX security_audit_is_acknowledged_idx ON security_audit (is_acknowledged);
