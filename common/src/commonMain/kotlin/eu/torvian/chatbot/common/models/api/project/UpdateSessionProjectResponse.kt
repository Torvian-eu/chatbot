package eu.torvian.chatbot.common.models.api.project

import kotlinx.serialization.Serializable

/**
 * Response body for selecting or deselecting a chat session's project.
 *
 * Carries the **resulting** session state after the project-selection mutation, including any
 * server-side role cleanup: when the newly selected project (or "No project") makes the session's
 * attached role illegal, the server clears the role in the same transaction and [agentRoleId]
 * reflects that. The client updates its cached session in one round-trip from this response.
 *
 * @property projectId The session's project after the mutation (may be null when deselected).
 * @property agentRoleId The session's attached agent role after the mutation; `null` when the role
 *            was cleared because the pair became illegal (or was already absent).
 */
@Serializable
data class UpdateSessionProjectResponse(
    val projectId: Long?,
    val agentRoleId: Long?
)