ALTER TABLE local_mcp_servers ADD COLUMN worker_id BIGINT;
ALTER TABLE local_mcp_servers ADD COLUMN name VARCHAR(255) NOT NULL DEFAULT 'Legacy MCP Server';
ALTER TABLE local_mcp_servers ADD COLUMN description TEXT;
ALTER TABLE local_mcp_servers ADD COLUMN command TEXT NOT NULL DEFAULT '';
ALTER TABLE local_mcp_servers ADD COLUMN arguments_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE local_mcp_servers ADD COLUMN working_directory TEXT;
ALTER TABLE local_mcp_servers ADD COLUMN auto_start_on_enable BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE local_mcp_servers ADD COLUMN auto_start_on_launch BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE local_mcp_servers ADD COLUMN auto_stop_after_inactivity_seconds INTEGER;
ALTER TABLE local_mcp_servers ADD COLUMN tool_name_prefix VARCHAR(255);
ALTER TABLE local_mcp_servers ADD COLUMN environment_variables_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE local_mcp_servers ADD COLUMN secret_environment_variables_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE local_mcp_servers ADD COLUMN created_at BIGINT NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER));
ALTER TABLE local_mcp_servers ADD COLUMN updated_at BIGINT NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER));

CREATE INDEX local_mcp_servers_worker_id_idx ON local_mcp_servers (worker_id);

