-- Store trusted and pending-trust IP records for authentication security checks
CREATE TABLE user_trusted_ips (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    is_trusted BOOLEAN NOT NULL DEFAULT 0,
    is_acknowledged BOOLEAN NOT NULL DEFAULT 1,
    first_used_at BIGINT NOT NULL,
    last_used_at BIGINT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX user_trusted_ips_user_id_ip_address_unique
    ON user_trusted_ips (user_id, ip_address);

