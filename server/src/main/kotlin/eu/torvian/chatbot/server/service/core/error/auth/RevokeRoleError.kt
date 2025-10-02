package eu.torvian.chatbot.server.service.core.error.auth

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur during role revocation.
 */
sealed interface RevokeRoleError {
    /**
     * Role with the specified ID was not found.
     *
     * @property roleId The ID of the role that was not found
     */
    data class RoleNotFound(val roleId: Long) : RevokeRoleError

    /**
     * Role is not assigned to the user.
     *
     * @property userId The user ID
     * @property roleId The role ID
     */
    data class RoleNotAssigned(val userId: Long, val roleId: Long) : RevokeRoleError

    /**
     * Cannot revoke admin role from the last administrator.
     *
     * @property userId The ID of the last admin user
     */
    data class CannotRevokeLastAdminRole(val userId: Long) : RevokeRoleError
}

/**
 * Extension function to convert RevokeRoleError to ApiError for HTTP responses.
 */
fun RevokeRoleError.toApiError(): ApiError = when (this) {
    is RevokeRoleError.RoleNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Role not found", "roleId" to roleId.toString())

    is RevokeRoleError.RoleNotAssigned ->
        apiError(
            CommonApiErrorCodes.NOT_FOUND,
            "Role not assigned to user",
            "userId" to userId.toString(),
            "roleId" to roleId.toString()
        )

    is RevokeRoleError.CannotRevokeLastAdminRole ->
        apiError(
            CommonApiErrorCodes.CONFLICT,
            "Cannot revoke admin role from the last administrator",
            "userId" to userId.toString()
        )
}
