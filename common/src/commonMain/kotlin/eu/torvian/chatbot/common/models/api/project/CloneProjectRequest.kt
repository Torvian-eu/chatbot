package eu.torvian.chatbot.common.models.api.project

import kotlinx.serialization.Serializable

/**
 * Request body for cloning an existing user-owned project.
 *
 * The source project's member agent roles are deep-copied as new role rows owned by the same user
 * and bound to the clone (single-project membership makes a re-link impossible); the source project
 * and its roles stay untouched. Only the owner of the source project may clone it (foreign or
 * nonexistent sources collapse to the same not-found outcome server-side).
 *
 * @property name The new project name, non-blank and at most 255 characters
 *            ([eu.torvian.chatbot.common.models.project.MAX_PROJECT_NAME_LENGTH]), unique among the
 *            owner's existing projects (enforced by the server).
 * @property description Optional description of the clone. `null` (omitted) copies the source
 *            project's description; a provided value — including an empty string — overrides it.
 */
@Serializable
data class CloneProjectRequest(
    val name: String,
    val description: String? = null
)