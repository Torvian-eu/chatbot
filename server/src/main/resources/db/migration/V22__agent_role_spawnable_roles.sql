-- Same-user role-to-role spawn permissions.
--
-- source_role_id is the role making a spawn request; target_role_id is the role it may spawn.
-- The composite primary key makes duplicate grants impossible and the relation is unordered, so no
-- position column exists. A role may grant spawn permission to any role it owns, including itself.
CREATE TABLE agent_role_spawnable_roles (
    source_role_id BIGINT NOT NULL,
    target_role_id BIGINT NOT NULL,
    PRIMARY KEY (source_role_id, target_role_id),
    FOREIGN KEY (source_role_id) REFERENCES agent_roles (id) ON DELETE CASCADE,
    FOREIGN KEY (target_role_id) REFERENCES agent_roles (id) ON DELETE CASCADE
);

CREATE INDEX agent_role_spawnable_roles_target_idx
    ON agent_role_spawnable_roles (target_role_id);
