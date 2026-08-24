-- Server built-in tool definitions: per-user instances of server-executed tools.
--
-- Server built-in tools (e.g. list_agent_roles, update_agent_role) are executed entirely in-process
-- on the server inside the chat turn. Each user gets their OWN base tool_definitions row, linked here
-- by user, so approval preferences (user_tool_approval_preferences composite key
-- (user_id, tool_definition_id)) and the per-user enable/disable flag stay naturally scoped to the
-- owning user. This mirrors the operator_tool_definitions side table exactly.

CREATE TABLE server_builtin_tool_definitions (
    tool_definition_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (tool_definition_id),
    FOREIGN KEY (tool_definition_id) REFERENCES tool_definitions (id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_server_builtin_tool_definitions_user ON server_builtin_tool_definitions (user_id);
