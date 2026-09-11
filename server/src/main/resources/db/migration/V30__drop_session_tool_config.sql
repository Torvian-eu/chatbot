-- Drop the session-specific tool configuration table.
--
-- A session's effective tools are resolved from its agent role
-- (`chat_sessions.agent_role_id` -> `agent_role_tools` -> `tool_definitions`) at
-- turn-preparation time. The direct session<->tool association in
-- `session_tool_config` is therefore no longer consulted and is removed outright.
-- Any per-session toggles stored here are discarded by design; users who need a
-- different tool set for a session must select or edit an agent role instead.
--
-- `IF EXISTS` keeps the migration idempotent for databases where the table was
-- never created. There is no data to migrate: role tool sets are independent of
-- session rows, and sessions carry no tool data besides `agent_role_id`.

DROP TABLE IF EXISTS session_tool_config;
