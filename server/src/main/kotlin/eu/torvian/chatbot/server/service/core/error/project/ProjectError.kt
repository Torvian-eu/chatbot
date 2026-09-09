package eu.torvian.chatbot.server.service.core.error.project

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when retrieving a project.
 */
sealed interface ProjectError {

    /**
     * The requested project was not found or is not owned by the requesting user.
     *
     * @property id The missing or foreign project identifier.
     */
    data class NotFound(val id: Long) : ProjectError
}

/**
 * Converts a [ProjectError] to its [ApiError] representation.
 *
 * A foreign project is reported identically to a nonexistent one, so ownership never leaks through
 * the error surface.
 */
fun ProjectError.toApiError(): ApiError = when (this) {
    is ProjectError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Project not found", "projectId" to id.toString())
}