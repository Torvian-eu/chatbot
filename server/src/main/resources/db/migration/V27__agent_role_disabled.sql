-- Per-user disabled marker for agent roles.
--
-- A row (role_id, user_id) means the role is DISABLED for that user; absence of a row means
-- ENABLED for that user. The disabled state deliberately lives out-of-band from the agent_roles row
-- (no column on agent_roles): agent roles are being prepared to become a shareable resource (like
-- providers/models/settings), and each sharing-visible user must keep their own enabled/disabled
-- state without ever touching the shared role row.
--
-- The composite primary key (role_id, user_id) makes duplicate rows for the same user impossible
-- while allowing different users to hold independent state for the same role. Deleting a role
-- cascades every user's disabled rows; deleting a user cascades that user's rows. Migrating an
-- existing database inserts no rows, so every role is enabled for every user after the upgrade.
CREATE TABLE agent_role_disabled (
    role_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, user_id),
    FOREIGN KEY (role_id) REFERENCES agent_roles (id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);