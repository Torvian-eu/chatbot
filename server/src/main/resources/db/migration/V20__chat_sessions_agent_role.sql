-- Session integration with agent roles.
--
-- A chat session no longer stores its own model/settings selection; it references an agent role that
-- bundles model, settings, tools and the composed system prompt. Model/settings/tools are resolved
-- from the role at turn-preparation time.
--
--   * `chat_sessions.agent_role_id`     -- nullable FK; ON DELETE SET NULL so deleting a role simply
--                                         unassigns it from sessions (they become non-sendable until a
--                                         role is re-selected).
--   * `chat_sessions.current_model_id` / `current_settings_id` -- removed; superseded by the role.
--   * `assistant_messages.agent_role_id` -- nullable provenance column recording which role produced an
--                                         assistant message (enables per-message role auditing).
--
-- SQLite refuses `ALTER TABLE ... DROP COLUMN` for columns that participate in a table-level FOREIGN
-- KEY constraint (which is how the baseline schema declared current_model_id/current_settings_id), so
-- the columns are removed via the standard 12-step table rebuild: create the new shape, copy data,
-- drop the old table and rename (the same pattern V10 already uses for `security_audit`).
--
-- The `DROP TABLE chat_sessions` below is safe because migration connections open with foreign key
-- enforcement OFF by default (the xerial SQLite driver default; `DatabaseMigrator` does not enable
-- it). With enforcement ON, `DROP TABLE` would perform an implicit DELETE of every row and cascade
-- into the child tables that reference chat_sessions with ON DELETE CASCADE (`chat_messages`,
-- `chat_session_owners`, `session_tool_config`, `session_current_leaf`), silently destroying their
-- data. The pragma cannot be toggled inside this file to make that explicit — mixing the
-- non-transactional PRAGMA with transactional DDL is rejected by Flyway (`mixed=false`), and the
-- pragma is a no-op inside a transaction anyway — so the migration relies on the connection default,
-- exactly like V10.
--
-- With enforcement off, dropping the old table leaves the child rows untouched, and their
-- `REFERENCES chat_sessions (id)` clauses (resolved by name) re-point to the renamed table whose
-- primary keys were copied verbatim, so referential integrity is preserved for the runtime
-- connections, which DO enforce foreign keys.

ALTER TABLE assistant_messages ADD COLUMN agent_role_id BIGINT REFERENCES agent_roles (id) ON DELETE SET NULL;

CREATE TABLE chat_sessions_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    group_id BIGINT,
    agent_role_id BIGINT,
    FOREIGN KEY (group_id) REFERENCES chat_groups (id) ON DELETE SET NULL,
    FOREIGN KEY (agent_role_id) REFERENCES agent_roles (id) ON DELETE SET NULL
);

INSERT INTO chat_sessions_new (id, name, created_at, updated_at, group_id, agent_role_id)
    SELECT id, name, created_at, updated_at, group_id, NULL FROM chat_sessions;

DROP TABLE chat_sessions;
ALTER TABLE chat_sessions_new RENAME TO chat_sessions;

CREATE INDEX chat_sessions_group_id_idx ON chat_sessions (group_id);
CREATE INDEX chat_sessions_agent_role_id_idx ON chat_sessions (agent_role_id);
