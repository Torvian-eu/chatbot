package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for the per-user disabled marker of agent roles.
 *
 * Presence of a `(role_id, user_id)` row means the role is disabled **for that user**; absence means
 * enabled. The state lives out-of-band from the `agent_roles` row so future role sharing (the
 * Owners/Access pattern used by providers/models/settings) keeps per-user enabled/disabled state
 * without touching the shared role row. The composite primary key prevents duplicate rows for the
 * same user while different users may hold independent state for the same role; both foreign keys
 * cascade on delete (a deleted role drops all users' markers, a deleted user drops their own).
 *
 * @property roleId Reference to the disabled agent role (`CASCADE` on delete).
 * @property userId Reference to the user the disabled state applies to (`CASCADE` on delete).
 */
object AgentRoleDisabledTable : Table("agent_role_disabled") {
    val roleId = reference("role_id", AgentRoleTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(roleId, userId)
}