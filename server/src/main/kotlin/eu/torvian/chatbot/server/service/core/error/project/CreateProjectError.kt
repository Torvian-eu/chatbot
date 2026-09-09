package eu.torvian.chatbot.server.service.core.error.project

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when creating a project.
 */
sealed interface CreateProjectError {

    /**
     * The provided project name is invalid (blank or too long).
     *
     * @property name The invalid project name.
     * @property reason Human-readable explanation of why the name is invalid.
     */
    data class InvalidName(val name: String, val reason: String) : CreateProjectError

    /**
     * A project with the specified name already exists for this owner.
     *
     * @property name The conflicting project name.
     */
    data class NameAlreadyExists(val name: String) : CreateProjectError

    /**
     * One of the referenced agent roles does not exist or is not owned by the requesting user.
     *
     * The same not-found shape covers both a missing and a foreign id, so the request cannot tell an
     * ownership mismatch apart from a plain non-existent role (no existence leak, mirrors the
     * agent-role project-validation convention).
     *
     * @property roleId The missing or foreign agent-role identifier.
     */
    data class RoleNotFound(val roleId: Long) : CreateProjectError

    /**
     * A requested member role already belongs to a DIFFERENT project.
     *
     * Roles have single-project membership, so attaching a project-bound role to this project would
     * silently move it out of its current project. The caller must detach it there first.
     *
     * @property roleId The role identifier that is bound to another project.
     */
    data class RoleInAnotherProject(val roleId: Long) : CreateProjectError

    /**
     * The ownership link for the newly created project could not be inserted.
     *
     * @property reason Human-readable explanation of the failure.
     */
    data class OwnerInsertFailed(val reason: String) : CreateProjectError
}

/**
 * Converts a [CreateProjectError] to its [ApiError] representation.
 */
fun CreateProjectError.toApiError(): ApiError = when (this) {
    is CreateProjectError.InvalidName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid project name: $reason", "name" to name)

    is CreateProjectError.NameAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Project name already exists", "name" to name)

    is CreateProjectError.RoleNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Agent role not found", "roleId" to roleId.toString())

    is CreateProjectError.RoleInAnotherProject ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Agent role already belongs to another project",
            "roleId" to roleId.toString()
        )

    is CreateProjectError.OwnerInsertFailed ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to set project ownership: $reason")
}