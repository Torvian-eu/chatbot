-- Predefined model presets, and the agent-role switch to a single preset reference.
--
-- A `ModelPreset` is a named, per-user bundle of "one LLM model + one settings profile". It is now
-- the SOLE source of truth for an agent role's LLM configuration: re-pointing a preset switches every
-- role bound to it in one operation, which is the whole point of the feature (switching roles to a
-- newly released model, or to a variant settings profile that only differs in
-- `customParams = {"provider":{"only":["<provider name>"]}}`, at once).
--
-- This migration therefore:
--   * creates `model_presets` -- name (per-owner uniqueness is service-enforced; the column is NOT
--     unique) plus optional display_name/description and the two nullable references. Both
--     references use ON DELETE SET NULL: deleting the referenced model/settings nulls the column and
--     keeps the preset row, so a preset can outlive its references (it stays attachable but cannot
--     drive a turn). There is deliberately NO CASCADE anywhere in this relation: deleting a preset
--     must never delete, disable or otherwise edit a role beyond nulling its reference.
--   * creates `model_preset_owners` -- single-owner layout mirroring `project_owners` /
--     `agent_role_owners` (preset_id is the primary key). Presets are user-wide, not project-scoped
--     and not shareable, so there are no access-grant tables and no new permission.
--   * rebuilds `agent_roles` so it keeps exactly ONE configuration reference, `model_preset_id`
--     (ON DELETE SET NULL), and LOSES `model_id` / `model_settings_id` and the
--     `agent_roles_model_settings_id_idx` index.
--
-- SQLite refuses `ALTER TABLE ... DROP COLUMN` for a column that participates in a table-level
-- FOREIGN KEY constraint or that is indexed (confirmed against the SQLite documentation,
-- *ALTER TABLE DROP COLUMN*). `agent_roles.model_id` and `agent_roles.model_settings_id` are named in
-- the table-level FOREIGN KEY clauses declared by V19 and `model_settings_id` additionally carries
-- `agent_roles_model_settings_id_idx`, so the columns are removed with the standard 12-step table
-- rebuild already used by V10 and V20: create the new shape, copy the data, drop the old table and
-- rename.
--
-- `DROP TABLE agent_roles` is safe ONLY because migration connections open with foreign key
-- enforcement OFF (the xerial SQLite driver default; `DatabaseMigrator` never enables it) — the same
-- reliance V10 and V20 document. With enforcement ON, `DROP TABLE` would perform an implicit DELETE
-- of every role row and cascade into the child tables that reference `agent_roles` with ON DELETE
-- CASCADE, silently destroying their data. Six tables reference `agent_roles` and must survive this
-- rebuild: `agent_role_tools.role_id`, `agent_role_owners.role_id`,
-- `agent_role_spawnable_roles.source_role_id`/`target_role_id`, `agent_role_disabled.role_id` (all
-- CASCADE), and `chat_sessions.agent_role_id` / `assistant_messages.agent_role_id` (SET NULL). With
-- enforcement off, dropping the old table leaves those rows untouched, and their
-- `REFERENCES agent_roles (id)` clauses (resolved by name) re-point to the renamed table whose
-- primary keys were copied verbatim — so referential integrity is preserved for the runtime
-- connections, which DO enforce foreign keys. The PRAGMA cannot be toggled inside this file to make
-- that explicit: mixing the non-transactional PRAGMA with transactional DDL is rejected by Flyway
-- (`mixed=false`), and the pragma is a no-op inside a transaction anyway.
--
-- The rebuild DELIBERATELY does not migrate the legacy configuration: no preset row is generated and
-- every pre-existing role ends with `model_preset_id = NULL`, because the preset is now the sole
-- source of truth and there is no dormant fallback pair (the columns holding it are gone). The
-- consequence is that pre-existing roles become NON-SENDABLE until a preset is created and attached
-- by hand, and the discarded `(model_id, model_settings_id)` values are NOT recoverable from the
-- database after this upgrade. Anyone who needs that mapping must record it before upgrading:
--
--     SELECT id, model_id, model_settings_id FROM agent_roles;
--
-- Downgrade is not supported: rolling back would need a second rebuild that cannot restore the
-- discarded configuration.
--
-- Runtime connections enforce foreign keys, so deleting a preset nulls `agent_roles.model_preset_id`
-- (the role survives, non-sendable), deleting a settings profile nulls `model_presets.model_settings_id`,
-- and deleting a model nulls `model_presets.model_id` (and, via `model_settings.model_id` CASCADE,
-- usually also the settings rows, hence `model_presets.model_settings_id`).

CREATE TABLE model_presets (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    name              VARCHAR(255) NOT NULL,
    display_name      VARCHAR(255),
    description       TEXT NOT NULL DEFAULT '',
    model_id          BIGINT,
    model_settings_id BIGINT,
    created_at        BIGINT NOT NULL,
    updated_at        BIGINT NOT NULL,
    FOREIGN KEY (model_id)          REFERENCES llm_models (id)     ON DELETE SET NULL,
    FOREIGN KEY (model_settings_id) REFERENCES model_settings (id) ON DELETE SET NULL
);

-- Non-unique name index: name uniqueness is scoped per owner user and enforced by the service (the
-- DB cannot express a per-user unique constraint because ownership lives in the separate
-- `model_preset_owners` table) — the same arrangement as `agent_roles.name` / `projects.name`.
CREATE INDEX model_presets_name_idx ON model_presets (name);
CREATE INDEX model_presets_model_id_idx ON model_presets (model_id);
CREATE INDEX model_presets_model_settings_id_idx ON model_presets (model_settings_id);

-- Ownership: exactly one owner per preset (preset_id is the primary key, mirroring project_owners /
-- agent_role_owners). Deleting the preset or the user cascades the owner row away.
CREATE TABLE model_preset_owners (
    preset_id BIGINT NOT NULL,
    user_id   BIGINT NOT NULL,
    PRIMARY KEY (preset_id),
    FOREIGN KEY (preset_id) REFERENCES model_presets (id) ON DELETE CASCADE,
    FOREIGN KEY (user_id)   REFERENCES users (id)         ON DELETE CASCADE
);

-- Rebuild agent_roles with a single configuration reference. `model_id` / `model_settings_id` are
-- absent from the new shape, so any code that still reads or writes them fails to compile.
CREATE TABLE agent_roles_new (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    name              VARCHAR(255) NOT NULL,
    display_name      VARCHAR(255),
    description       TEXT NOT NULL DEFAULT '',
    model_preset_id   BIGINT,
    instructions_json TEXT NOT NULL DEFAULT '[]',
    created_at        BIGINT NOT NULL,
    updated_at        BIGINT NOT NULL,
    project_id        BIGINT,
    FOREIGN KEY (model_preset_id) REFERENCES model_presets (id) ON DELETE SET NULL,
    FOREIGN KEY (project_id)      REFERENCES projects (id)      ON DELETE SET NULL
);

-- Every pre-existing role is copied with model_preset_id = NULL (the deliberate non-migration
-- documented above); all other properties, the id and the primary key are carried over verbatim,
-- which is what lets the six child tables' `REFERENCES agent_roles (id)` clauses keep resolving after
-- the rename. SQLite updates `sqlite_sequence` on these explicit-id inserts, so ids handed out after
-- the upgrade continue above the previous maximum.
INSERT INTO agent_roles_new
    (id, name, display_name, description, model_preset_id, instructions_json, created_at, updated_at, project_id)
    SELECT id, name, display_name, description, NULL, instructions_json, created_at, updated_at, project_id
    FROM agent_roles;

DROP TABLE agent_roles;
ALTER TABLE agent_roles_new RENAME TO agent_roles;

-- Indexes: the same names for the surviving columns, plus the new preset reference index.
-- `agent_roles_model_settings_id_idx` is intentionally NOT recreated (the column is gone).
CREATE INDEX agent_roles_name_idx ON agent_roles (name);
CREATE INDEX agent_roles_project_id_idx ON agent_roles (project_id);
CREATE INDEX agent_roles_model_preset_id_idx ON agent_roles (model_preset_id);
