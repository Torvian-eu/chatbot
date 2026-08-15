package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for agent-role ownership links.
 *
 * Agent roles are per-user in this stage: a role has exactly one owner, mirroring the
 * `chat_session_owners` family (`role_id` is the primary key). A future `project_id` column on
 * [AgentRoleTable] can extend ownership semantics later.
 *
 * @property roleId Reference to the owned agent role (primary key, `CASCADE` on delete).
 * @property userId Reference to the owning user (`CASCADE` on delete).
 */
object AgentRoleOwnersTable : Table("agent_role_owners") {
    val roleId = reference("role_id", AgentRoleTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(roleId)
}
