package eu.torvian.chatbot.server.service.core.error.auth

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when deleting a role.
 */
sealed interface DeleteRoleError {
    /**
     * Role with the specified ID was not found.
     *
     * @property roleId The role ID that was not found
     */
    data class RoleNotFound(val roleId: Long) : DeleteRoleError

    /**
     * Attempted to delete a system role, which is protected.
     *
     * @property roleName The protected system role name
     */
    data class SystemRoleProtected(val roleName: String) : DeleteRoleError

    /**
     * Cannot delete role because it is assigned to one or more users.
     *
     * @property roleId The role ID that is still in use
     * @property userCount The number of users that have this role
     */
    data class RoleInUse(val roleId: Long, val userCount: Int) : DeleteRoleError
}

/**
 * Extension function to convert [DeleteRoleError] to [ApiError].
 */
fun DeleteRoleError.toApiError(): ApiError = when (this) {
    is DeleteRoleError.RoleNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Role not found", "roleId" to roleId.toString())

    is DeleteRoleError.SystemRoleProtected ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Cannot delete system role", "roleName" to roleName)

    is DeleteRoleError.RoleInUse ->
        apiError(
            CommonApiErrorCodes.CONFLICT,
            "Cannot delete role that is assigned to users",
            "roleId" to roleId.toString(),
            "userCount" to userCount.toString()
        )
}
