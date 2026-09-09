package eu.torvian.chatbot.server.service.core.error.project

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when deleting a project.
 *
 * Deleting a project is non-destructive for roles: their `project_id` is nulled (ON DELETE SET
 * NULL), but the roles themselves survive.
 * Sessions that selected the project get `project_id = NULL` via the FK,
 * and the service clears their roles in the same transaction (uniform legality restoration), so no "project
 * in use" rejection is needed.
 */
sealed interface DeleteProjectError {

    /**
     * The project to delete was not found or is not owned by the requesting user.
     *
     * @property id The missing or foreign project identifier.
     */
    data class NotFound(val id: Long) : DeleteProjectError
}

/**
 * Converts a [DeleteProjectError] to its [ApiError] representation.
 *
 * A foreign project is reported identically to a nonexistent one, so ownership never leaks through
 * the error surface.
 */
fun DeleteProjectError.toApiError(): ApiError = when (this) {
    is DeleteProjectError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Project not found", "projectId" to id.toString())
}