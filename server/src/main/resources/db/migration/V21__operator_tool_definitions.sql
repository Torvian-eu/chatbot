-- Operator tool definitions: per-user instances of operator-executed tools (e.g. spawn_agent).
--
-- Operator tools are server-orchestrated: after approval the server relays the tool call to the
-- operator (in v1 the client app), who runs the tool and returns the result over the chat socket.
-- Each user gets their OWN base tool_definitions row, linked here by user, so approval preferences
-- (user_tool_approval_preferences composite key (user_id, tool_definition_id)) and the per-user
-- enable/disable flag stay naturally scoped to the owning user.

CREATE TABLE operator_tool_definitions (
    tool_definition_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (tool_definition_id),
    FOREIGN KEY (tool_definition_id) REFERENCES tool_definitions (id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_operator_tool_definitions_user ON operator_tool_definitions (user_id);
