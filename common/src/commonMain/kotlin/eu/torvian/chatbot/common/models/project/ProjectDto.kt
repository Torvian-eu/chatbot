package eu.torvian.chatbot.common.models.project

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Maximum number of characters allowed in a project name.
 *
 * This mirrors the `name` varchar(255) column of the server's `projects` table (see
 * `ProjectTable`). It is shared by UI text-field limits and server validation so both layers stay
 * consistent with the database schema.
 */
const val MAX_PROJECT_NAME_LENGTH = 255

/**
 * Shared, serializable representation of a user-owned project.
 *
 * A project is a named collection a user uses to group several agent roles together. Each project
 * is owned by exactly one user (the link lives in the `project_owners` table); users only ever see
 * their own projects. A project carries no model/settings/tools itself — membership is the whole
 * relation, stored as the single nullable `agent_roles.project_id` column (a role belongs to at most
 * one project) and editable from either side: agent-role requests carry `projectId`, project
 * requests carry `agentRoleIds`.
 *
 * @property id Immutable, database-generated identifier.
 * @property name The project name. Unique per owner user, enforced by the server.
 * @property description Free-form description of the project's purpose.
 * @property createdAt Timestamp when the project was created.
 * @property agentRoleIds Unordered set of agent-role identifiers belonging to this project. The
 *            server populates it batch-wise so list endpoints avoid an N+1 read; the client uses it
 *            to render the project's member roles (e.g. the Settings → Projects detail panel).
 */
@Serializable
data class ProjectDto(
    val id: Long,
    val name: String,
    val description: String = "",
    val createdAt: Instant,
    val agentRoleIds: Set<Long> = emptySet()
)