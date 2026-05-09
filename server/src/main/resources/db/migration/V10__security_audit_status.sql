-- Security audit table schema update: replace is_acknowledged with status and resolved_at.
-- This enables three states: PENDING, TRUSTED, and DISMISSED.

-- SQLite doesn't support DROP COLUMN, so we need to recreate the table
-- First, create a new table with the updated schema
CREATE TABLE security_audit_new (
    id INTEGER PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(36) NOT NULL,
    ip_address VARCHAR(45),
    created_at BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    resolved_at BIGINT,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Migrate existing data: acknowledged records become TRUSTED, pending remain PENDING
INSERT INTO security_audit_new (id, user_id, device_id, ip_address, created_at, status, resolved_at)
SELECT id, user_id, device_id, ip_address, created_at,
       CASE WHEN is_acknowledged = 1 THEN 'TRUSTED' ELSE 'PENDING' END,
       CASE WHEN is_acknowledged = 1 THEN created_at ELSE NULL END
FROM security_audit;

-- Drop the old table
DROP TABLE security_audit;

-- Rename the new table to the original name
ALTER TABLE security_audit_new RENAME TO security_audit;

-- Recreate indexes
CREATE INDEX security_audit_user_id_idx ON security_audit (user_id);
CREATE INDEX security_audit_status_idx ON security_audit (status);
