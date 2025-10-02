package eu.torvian.chatbot.server.service.core.error.auth

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur during role assignment.
 */
sealed interface AssignRoleError {
    /**
     * User or role was not found.
     *
     * @property userId The user ID
     * @property roleId The role ID
     */
    data class UserOrRoleNotFound(val userId: Long, val roleId: Long) : AssignRoleError

    /**
     * Role is already assigned to the user.
     *
     * @property userId The user ID
     * @property roleId The role ID
     */
    data class RoleAlreadyAssigned(val userId: Long, val roleId: Long) : AssignRoleError
}

/**
 * Extension function to convert AssignRoleError to ApiError for HTTP responses.
 */
fun AssignRoleError.toApiError(): ApiError = when (this) {
    is AssignRoleError.UserOrRoleNotFound ->
        apiError(
            CommonApiErrorCodes.NOT_FOUND,
            "User or role not found",
            "userId" to userId.toString(),
            "roleId" to roleId.toString()
        )

    is AssignRoleError.RoleAlreadyAssigned ->
        apiError(
            CommonApiErrorCodes.CONFLICT,
            "Role already assigned to user",
            "userId" to userId.toString(),
            "roleId" to roleId.toString()
        )
}
