package eu.torvian.chatbot.server.service.core.error.project

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when cloning a project.
 */
sealed interface CloneProjectError {

    /**
     * The project to clone does not exist or is not owned by the requesting user.
     *
     * A foreign and a nonexistent project collapse into the same error so ownership never leaks
     * through the clone surface (mirrors the other project operations).
     *
     * @property id The missing or foreign source project identifier.
     */
    data class NotFound(val id: Long) : CloneProjectError

    /**
     * The provided name for the clone is invalid (blank or too long).
     *
     * @property name The invalid project name.
     * @property reason Human-readable explanation of why the name is invalid.
     */
    data class InvalidName(val name: String, val reason: String) : CloneProjectError

    /**
     * A project with the specified name already exists for this owner.
     *
     * @property name The conflicting project name.
     */
    data class NameAlreadyExists(val name: String) : CloneProjectError

    /**
     * The ownership link for the newly created clone could not be inserted.
     *
     * @property reason Human-readable explanation of the failure.
     */
    data class OwnerInsertFailed(val reason: String) : CloneProjectError
}

/**
 * Converts a [CloneProjectError] to its [ApiError] representation.
 *
 * A foreign source project is reported identically to a nonexistent one, so ownership never leaks
 * through the error surface.
 */
fun CloneProjectError.toApiError(): ApiError = when (this) {
    is CloneProjectError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Project not found", "projectId" to id.toString())

    is CloneProjectError.InvalidName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid project name: $reason", "name" to name)

    is CloneProjectError.NameAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Project name already exists", "name" to name)

    is CloneProjectError.OwnerInsertFailed ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to set project ownership: $reason")
}