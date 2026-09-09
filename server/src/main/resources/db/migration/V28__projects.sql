-- User-owned projects: named collections that group agent roles together, plus single-project
-- role membership and a per-session project selection.
--
-- This migration is purely additive (no table rebuild, no data migration): it introduces the
-- `projects` resource, the single-owner `project_owners` table, a single nullable `project_id`
-- membership column directly on `agent_roles`, and a nullable per-session project selection on
-- `chat_sessions`. Existing sessions get `project_id = NULL` after the upgrade and every existing
-- role stays unassociated (`project_id = NULL`) — no retroactive reassignment.
--
-- Membership is deliberately ONE project per role (`projectId: Long?` on the wire, null =
-- unassociated), not a set: "spawnable targets belong to the same project" is a single concrete
-- rule only when a role has an unambiguous project. The single column cannot hold two ids, so a
-- role belongs to at most one project by construction. `ON DELETE SET NULL` keeps the delete
-- semantics simple: deleting a project unassociates its member roles and clears its sessions'
-- selection, while the roles and sessions themselves survive.
--
-- `name` is NOT unique on the column: project-name uniqueness is scoped per owner user and enforced
-- by ProjectServiceImpl (the DB cannot express it because ownership lives in the separate
-- `project_owners` table) — the same arrangement as `agent_roles.name`. A plain index speeds up the
-- per-owner name check.
CREATE TABLE projects (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        VARCHAR(255) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at  BIGINT NOT NULL,
    updated_at  BIGINT NOT NULL
);

CREATE INDEX projects_name_idx ON projects (name);

-- Ownership: exactly one owner per project. The project_id is the primary key (mirroring
-- agent_role_owners / chat_session_owners): a project can never have two owners, and deleting a
-- project or a user cascades away the corresponding owner rows.
CREATE TABLE project_owners (
    project_id BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    PRIMARY KEY (project_id),
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    FOREIGN KEY (user_id)    REFERENCES users (id)    ON DELETE CASCADE
);

-- Single role membership: one nullable project per role. Nullable ADD COLUMN carrying a REFERENCES
-- clause is legal: no NOT NULL constraint and no non-NULL default, so SQLite accepts it without a
-- table rebuild. A role belongs to at most one project by construction; NULL means unassociated (a
-- legal state that keeps the role visible for project-less sessions). ON DELETE SET NULL mirrors the
-- old plurality: deleting a project unassociates its member roles without deleting them. Runtime
-- connections enforce foreign keys, giving the SET NULL when a project is deleted.
--
-- The agent_roles table has no project column yet (V19 created it without one), so the FK is
-- created in the same ALTER instead of a later ADD CONSTRAINT — exactly the `chat_sessions` pattern
-- below.
ALTER TABLE agent_roles ADD COLUMN project_id BIGINT REFERENCES projects (id) ON DELETE SET NULL;
CREATE INDEX agent_roles_project_id_idx ON agent_roles (project_id);

-- Per-session project selection. Nullable ADD COLUMN carrying a REFERENCES clause is legal for the
-- same reason as above. Runtime connections enforce foreign keys, giving ON DELETE SET NULL when a
-- project is deleted: affected sessions keep their messages and become inert (their role is cleared
-- by the service in the same transaction).
ALTER TABLE chat_sessions ADD COLUMN project_id BIGINT REFERENCES projects (id) ON DELETE SET NULL;
CREATE INDEX chat_sessions_project_id_idx ON chat_sessions (project_id);