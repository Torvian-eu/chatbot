package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for project ownership links.
 *
 * Projects are per-user in this stage: a project has exactly one owner, mirroring the
 * `agent_role_owners`/`chat_session_owners` family (`project_id` is the primary key).
 *
 * @property projectId Reference to the owned project (primary key, `CASCADE` on delete).
 * @property userId Reference to the owning user (`CASCADE` on delete).
 */
object ProjectOwnersTable : Table("project_owners") {
    val projectId = reference("project_id", ProjectTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(projectId)
}