-- User-defined agent roles.
--
-- An agent role bundles everything an LLM-powered conversation needs (model, settings, tools and a
-- composed system prompt) into one reusable, user-managed entity. A chat session references a role
-- instead of storing model/settings/tools directly.
--
-- Storage is deliberately simple:
--   * `agent_role_tools`     -- join table of tool_definition ids attached to a role (Set<Long>),
--                              unordered. ON DELETE CASCADE on both sides: deleting a tool definition
--                              removes it from every role (no dangling ids, no application sweep), and
--                              deleting a role removes its tool rows. Duplicate (role, tool) pairs are
--                              impossible (primary key).
--   * `instructions_json`    -- JSON array of the flat, type-tagged AgentInstructionDto list (the same
--                              encoding used on the wire). No polymorphic codec is needed: the server
--                              domain `AgentInstruction` hierarchy is never serialized.
--
-- `name` is NOT globally unique: the documented contract is "unique per user", so different users
-- may create roles with the same name. A plain index speeds up per-user name lookups; the per-user
-- uniqueness check itself lives in AgentRoleServiceImpl (the DB cannot express it because ownership
-- is stored in the separate agent_role_owners table).
--
-- `model_id`/`model_settings_id` are nullable and use ON DELETE SET NULL: deleting a referenced
-- model/settings nulls the role's fields instead of blocking the delete. The role then becomes
-- non-sendable until repaired (a new model/settings selected).

CREATE TABLE agent_roles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    description TEXT NOT NULL DEFAULT '',
    model_id BIGINT,
    model_settings_id BIGINT,
    instructions_json TEXT NOT NULL DEFAULT '[]',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    FOREIGN KEY (model_id) REFERENCES llm_models (id) ON DELETE SET NULL,
    FOREIGN KEY (model_settings_id) REFERENCES model_settings (id) ON DELETE SET NULL
);

CREATE INDEX agent_roles_name_idx ON agent_roles (name);
CREATE INDEX agent_roles_model_settings_id_idx ON agent_roles (model_settings_id);

-- Role -> enabled tools. CASCADE both ways: deleting a tool definition removes it from every role
-- (no dangling ids, no application sweep); deleting a role removes its tool rows. The tool set is
-- deliberately unordered (mirrors Set<Long> on the wire); the primary key makes duplicate (role, tool)
-- pairs impossible. `tool_definitions` already exists (created in V14), so statement order is fine.
CREATE TABLE agent_role_tools (
    role_id            BIGINT NOT NULL,
    tool_definition_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, tool_definition_id),
    FOREIGN KEY (role_id)            REFERENCES agent_roles (id)       ON DELETE CASCADE,
    FOREIGN KEY (tool_definition_id) REFERENCES tool_definitions (id)  ON DELETE CASCADE
);

CREATE INDEX agent_role_tools_tool_id_idx ON agent_role_tools (tool_definition_id);

-- Ownership: agent roles are per-user in this stage. The role_id is the primary key, mirroring the
-- chat_session_owners family (a role has exactly one owner).
CREATE TABLE agent_role_owners (
    role_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (role_id),
    FOREIGN KEY (role_id) REFERENCES agent_roles (id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
