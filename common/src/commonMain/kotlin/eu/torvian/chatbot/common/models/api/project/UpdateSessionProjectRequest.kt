package eu.torvian.chatbot.common.models.api.project

import kotlinx.serialization.Serializable

/**
 * Request body for selecting or deselecting the project of a chat session.
 *
 * The selection is per session and server-persisted on `chat_sessions.project_id`. Selecting a
 * project filters the session's offered agent roles to that project's roles; selecting `null`
 * ("No project") filters them to roles with no project association.
 *
 * @property projectId Identifier of the user-owned project to select, or `null` to deselect. A
 *            foreign or nonexistent project is rejected as not-found by the server.
 */
@Serializable
data class UpdateSessionProjectRequest(
    val projectId: Long?
)