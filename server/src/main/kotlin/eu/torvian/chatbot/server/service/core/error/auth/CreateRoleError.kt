package eu.torvian.chatbot.server.service.core.error.auth

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when creating a role.
 */
sealed interface CreateRoleError {
    /**
     * A role with the specified name already exists.
     *
     * @property roleName The role name that already exists
     */
    data class RoleNameAlreadyExists(val roleName: String) : CreateRoleError

    /**
     * The provided role name is invalid.
     *
     * @property roleName The invalid role name
     * @property reason Human-readable explanation of why the name is invalid
     */
    data class InvalidRoleName(val roleName: String, val reason: String) : CreateRoleError
}

/**
 * Extension function to convert [CreateRoleError] to [ApiError].
 */
fun CreateRoleError.toApiError(): ApiError = when (this) {
    is CreateRoleError.RoleNameAlreadyExists ->
        apiError(CommonApiErrorCodes.CONFLICT, "Role name already exists", "roleName" to roleName)

    is CreateRoleError.InvalidRoleName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid role name: $reason", "roleName" to roleName)
}
