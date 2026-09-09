package eu.torvian.chatbot.server.service.core.error.project

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when updating a project.
 */
sealed interface UpdateProjectError {

    /**
     * The project to update was not found or is not owned by the requesting user.
     *
     * @property id The missing or foreign project identifier.
     */
    data class NotFound(val id: Long) : UpdateProjectError

    /**
     * The provided project name is invalid (blank or too long).
     *
     * @property name The invalid project name.
     * @property reason Human-readable explanation of why the name is invalid.
     */
    data class InvalidName(val name: String, val reason: String) : UpdateProjectError

    /**
     * A different project with the specified name already exists for this owner.
     *
     * @property name The conflicting project name.
     */
    data class NameAlreadyExists(val name: String) : UpdateProjectError

    /**
     * One of the referenced agent roles does not exist or is not owned by the requesting user.
     *
     * The same not-found shape covers both a missing and a foreign id, so the request cannot tell an
     * ownership mismatch apart from a plain non-existent role (no existence leak, mirrors the
     * agent-role project-validation convention).
     *
     * @property roleId The missing or foreign agent-role identifier.
     */
    data class RoleNotFound(val roleId: Long) : UpdateProjectError

    /**
     * A requested member role already belongs to a DIFFERENT project.
     *
     * Roles have single-project membership, so attaching a project-bound role to this project would
     * silently move it out of its current project. The caller must detach it there first.
     *
     * @property roleId The role identifier that is bound to another project.
     */
    data class RoleInAnotherProject(val roleId: Long) : UpdateProjectError
}

/**
 * Converts an [UpdateProjectError] to its [ApiError] representation.
 *
 * A foreign project is reported identically to a nonexistent one, so ownership never leaks through
 * the error surface.
 */
fun UpdateProjectError.toApiError(): ApiError = when (this) {
    is UpdateProjectError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Project not found", "projectId" to id.toString())

    is UpdateProjectError.InvalidName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid project name: $reason", "name" to name)

    is UpdateProjectError.NameAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Project name already exists", "name" to name)

    is UpdateProjectError.RoleNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Agent role not found", "roleId" to roleId.toString())

    is UpdateProjectError.RoleInAnotherProject ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Agent role already belongs to another project",
            "roleId" to roleId.toString()
        )
}