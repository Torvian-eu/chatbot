package eu.torvian.chatbot.server.service.core.error.session

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when updating the project selected for a chat session.
 *
 * Both failures map to `NOT_FOUND` (mirroring the [UpdateSessionAgentRoleIdError.AgentRoleNotFound]
 * precedent): a foreign or concurrently-deleted project is indistinguishable from a nonexistent one,
 * so ownership never leaks through this surface — the route verifies project ownership up front via
 * `ProjectService.getProjectById` and this error type only carries the DAO-level mapping.
 */
sealed interface UpdateSessionProjectIdError {

    /**
     * The session with the specified ID was not found.
     *
     * @property id The missing session identifier.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionProjectIdError

    /**
     * The referenced project does not exist (or was deleted concurrently between the route's
     * ownership check and the write).
     *
     * @property projectId The missing project identifier (may be a sentinel `0` when the request
     *            deselected with null but the write still failed on a foreign key — unreachable in
     *            practice, kept for exhaustiveness).
     */
    data class ProjectNotFound(val projectId: Long) : UpdateSessionProjectIdError
}

/**
 * Converts an [UpdateSessionProjectIdError] to its [ApiError] representation.
 */
fun UpdateSessionProjectIdError.toApiError(): ApiError = when (this) {
    is UpdateSessionProjectIdError.SessionNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Session not found",
        "sessionId" to id.toString()
    )

    is UpdateSessionProjectIdError.ProjectNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Project not found",
        "projectId" to projectId.toString()
    )
}