package eu.torvian.chatbot.common.models.api.project

import kotlinx.serialization.Serializable

/**
 * Request body for updating an existing user-owned project.
 *
 * A full replacement of `name`, `description` and the [agentRoleIds] membership: roles the project
 * no longer contains are detached from it, roles newly listed are attached. The server clears the
 * agent role of any session whose attached role left the project (Session Legality Invariant), so a
 * project-side membership edit can never leave an illegal (project, role) pair behind.
 *
 * @property name The new project name, non-blank and at most 255 characters
 *            ([eu.torvian.chatbot.common.models.project.MAX_PROJECT_NAME_LENGTH]). Must stay unique
 *            among the owner's projects (the project being updated is excluded from the check).
 * @property description The new free-form description of the project.
 * @property agentRoleIds The project's full new member-role set. Every id must belong to the
 *            requesting user (a missing or foreign id is rejected by the server). Defaults to an
 *            empty set (clears the membership).
 */
@Serializable
data class UpdateProjectRequest(
    val name: String,
    val description: String = "",
    val agentRoleIds: Set<Long> = emptySet()
)