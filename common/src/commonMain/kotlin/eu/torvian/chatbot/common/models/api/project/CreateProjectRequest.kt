package eu.torvian.chatbot.common.models.api.project

import kotlinx.serialization.Serializable

/**
 * Request body for creating a new user-owned project.
 *
 * The requesting user becomes the project's sole owner; the name must be unique among the user's
 * existing projects (enforced by the server). The optional [agentRoleIds] set is persisted as the
 * project's initial role membership in the same transaction, so a project can be created with its
 * member roles already attached — no separate role-edit round trip is needed.
 *
 * @property name The project name, non-blank and at most 255 characters ([eu.torvian.chatbot.common.models.project.MAX_PROJECT_NAME_LENGTH]).
 * @property description Free-form description of the project.
 * @property agentRoleIds Agent-role identifiers to attach to the project on creation. Every id must
 *            belong to the requesting user (a missing or foreign id is rejected by the server).
 *            Defaults to an empty set (a fresh project with no roles).
 */
@Serializable
data class CreateProjectRequest(
    val name: String,
    val description: String = "",
    val agentRoleIds: Set<Long> = emptySet()
)