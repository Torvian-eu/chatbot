-- Links credential profiles to the agent roles allowed to attach them.
--
-- The relation is many-to-many: one profile may be linked to several roles and one role to several
-- profiles. The composite primary key makes a duplicate link impossible at the DB level, and
-- `ON DELETE CASCADE` on both foreign keys keeps the relation free of dangling ids when a profile, a
-- worker or a role disappears.
--
-- No ordering column: a profile's role set is unordered (it mirrors a `Set<Long>` on the wire), so a
-- full replacement is a plain delete + insert.
--
-- No backfill by design: a profile with zero links is attachable by nobody, so every profile that
-- exists at upgrade time is a draft until an operator links roles to it.

CREATE TABLE worker_credential_profile_agent_roles (
    profile_id BIGINT NOT NULL,
    agent_role_id BIGINT NOT NULL,
    PRIMARY KEY (profile_id, agent_role_id),
    FOREIGN KEY (profile_id) REFERENCES worker_credential_profiles (id) ON DELETE CASCADE,
    FOREIGN KEY (agent_role_id) REFERENCES agent_roles (id) ON DELETE CASCADE
);

-- SQLite does not auto-create indexes on the child side of a foreign key; this one keeps the cascade
-- from agent_roles and the reverse lookup ("which profiles are linked to role X") cheap.
CREATE INDEX worker_credential_profile_agent_roles_agent_role_id_idx
    ON worker_credential_profile_agent_roles (agent_role_id);
