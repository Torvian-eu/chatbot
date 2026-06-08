-- Signature metadata table for Local MCP servers.
-- Links each server configuration to the device that signed it, enabling worker verification.
-- Multiple devices can have their own signatures for the same MCP server.
-- The payload JSON is stored to preserve the exact bytes signed by the app.

CREATE TABLE local_mcp_server_signatures (
    server_id BIGINT NOT NULL,
    user_device_id BIGINT NOT NULL,
    signature TEXT NOT NULL,
    timestamp BIGINT NOT NULL,
    nonce VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at BIGINT NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    updated_at BIGINT NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    FOREIGN KEY (server_id) REFERENCES local_mcp_servers (id) ON DELETE CASCADE,
    FOREIGN KEY (user_device_id) REFERENCES user_devices (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX local_mcp_server_signatures_server_id_user_device_id_unique
    ON local_mcp_server_signatures (server_id, user_device_id);
