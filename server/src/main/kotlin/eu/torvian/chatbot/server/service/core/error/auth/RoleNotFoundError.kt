package eu.torvian.chatbot.server.service.core.error.auth

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors when a role cannot be found.
 */
sealed interface RoleNotFoundError {
    /**
     * Role with the specified ID was not found.
     *
     * @property roleId The role ID that was not found
     */
    data class ById(val roleId: Long) : RoleNotFoundError

    /**
     * Role with the specified name was not found.
     *
     * @property roleName The role name that was not found
     */
    data class ByName(val roleName: String) : RoleNotFoundError
}

/**
 * Extension function to convert [RoleNotFoundError] to [ApiError].
 */
fun RoleNotFoundError.toApiError(): ApiError = when (this) {
    is RoleNotFoundError.ById ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Role not found", "roleId" to roleId.toString())

    is RoleNotFoundError.ByName ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Role not found", "roleName" to roleName)
}
