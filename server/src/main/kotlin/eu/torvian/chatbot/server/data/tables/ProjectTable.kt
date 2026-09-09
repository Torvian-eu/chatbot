package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for user-owned projects.
 *
 * A project is a named collection of agent roles. The role memberships live as the single nullable
 * `agent_roles.project_id` column (see [AgentRoleTable]) — a role belongs to at most one project —
 * and ownership lives in `project_owners` ([ProjectOwnersTable]). Only the flat fields are stored
 * here.
 *
 * @property name Machine-readable project name. Unique per owner user, enforced at the service layer
 *            (`ProjectServiceImpl`) — the column itself is deliberately NOT globally unique so
 *            different users may reuse the same name, mirroring `agent_roles.name`.
 * @property description Free-form description of the project.
 * @property createdAt Timestamp when the project was created.
 * @property updatedAt Timestamp when the project was last updated.
 */
object ProjectTable : LongIdTable("projects") {
    val name = varchar("name", 255)
    val description = text("description").default("")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    init {
        // Non-unique: name uniqueness is scoped per owner user and enforced by ProjectServiceImpl
        // (the DB cannot express a per-user unique constraint because ownership lives in a separate
        // table, mirroring agent_roles).
        index(isUnique = false, name)
    }
}