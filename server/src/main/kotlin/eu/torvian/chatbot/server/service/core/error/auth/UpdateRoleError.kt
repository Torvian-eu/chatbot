package eu.torvian.chatbot.server.service.core.error.auth

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when updating a role.
 */
sealed interface UpdateRoleError {
    /**
     * Role with the specified ID was not found.
     *
     * @property roleId The role ID that was not found
     */
    data class RoleNotFound(val roleId: Long) : UpdateRoleError

    /**
     * A role with the specified name already exists.
     *
     * @property roleName The role name that already exists
     */
    data class RoleNameAlreadyExists(val roleName: String) : UpdateRoleError

    /**
     * Attempted to change the name of a system role, which is protected.
     *
     * @property roleName The protected system role name
     */
    data class SystemRoleNameProtected(val roleName: String) : UpdateRoleError

    /**
     * The provided role name is invalid.
     *
     * @property roleName The invalid role name
     * @property reason Human-readable explanation of why the name is invalid
     */
    data class InvalidRoleName(val roleName: String, val reason: String) : UpdateRoleError
}

/**
 * Extension function to convert [UpdateRoleError] to [ApiError].
 */
fun UpdateRoleError.toApiError(): ApiError = when (this) {
    is UpdateRoleError.RoleNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Role not found", "roleId" to roleId.toString())

    is UpdateRoleError.RoleNameAlreadyExists ->
        apiError(CommonApiErrorCodes.CONFLICT, "Role name already exists", "roleName" to roleName)

    is UpdateRoleError.SystemRoleNameProtected ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Cannot change name of system role", "roleName" to roleName)

    is UpdateRoleError.InvalidRoleName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid role name: $reason", "roleName" to roleName)
}
