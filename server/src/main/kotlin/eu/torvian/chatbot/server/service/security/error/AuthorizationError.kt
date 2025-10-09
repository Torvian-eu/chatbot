package eu.torvian.chatbot.server.service.security.error

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.PermissionSpec
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur during authorization operations.
 */
sealed interface AuthorizationError {
    /**
     * User does not have the required permission.
     *
     * @property userId The ID of the user who was denied
     * @property action The action that was denied
     * @property subject The subject/resource that was denied
     */
    data class PermissionDenied(
        val userId: Long,
        val action: String,
        val subject: String
    ) : AuthorizationError

    /**
     * User does not have any of the required permissions.
     *
     * @property userId The ID of the user who was denied
     * @property permissions The list of permissions that were required
     */
    data class AnyPermissionDenied(
        val userId: Long,
        val permissions: List<PermissionSpec>
    ) : AuthorizationError

    /**
     * User does not have all of the required permissions.
     *
     * @property userId The ID of the user who was denied
     * @property permissions The list of permissions that were required
     */
    data class AllPermissionsDenied(
        val userId: Long,
        val permissions: List<PermissionSpec>
    ) : AuthorizationError

    /**
     * User does not have the required role.
     *
     * @property userId The ID of the user who was denied
     * @property roleName The name of the role that was required
     */
    data class RoleRequired(
        val userId: Long,
        val roleName: String
    ) : AuthorizationError

    /**
     * Role was not found in the system.
     *
     * @property roleName The name of the role that was not found
     */
    data class RoleNotFound(val roleName: String) : AuthorizationError
}

/**
 * Extension function to convert AuthorizationError to ApiError for HTTP responses.
 */
fun AuthorizationError.toApiError(): ApiError = when (this) {
    is AuthorizationError.PermissionDenied ->
        apiError(
            CommonApiErrorCodes.PERMISSION_DENIED,
            "Permission denied",
            "userId" to userId.toString(),
            "action" to action,
            "subject" to subject
        )

    is AuthorizationError.AnyPermissionDenied ->
        apiError(
            CommonApiErrorCodes.PERMISSION_DENIED,
            "Permission denied: user does not have any of the required permissions",
            "userId" to userId.toString(),
            "permissions" to permissions.joinToString { "(${it.action}, ${it.subject})" }
        )

    is AuthorizationError.AllPermissionsDenied ->
        apiError(
            CommonApiErrorCodes.PERMISSION_DENIED,
            "Permission denied: user does not have all of the required permissions",
            "userId" to userId.toString(),
            "permissions" to permissions.joinToString { "(${it.action}, ${it.subject})" }
        )

    is AuthorizationError.RoleRequired ->
        apiError(
            CommonApiErrorCodes.PERMISSION_DENIED,
            "Role required",
            "userId" to userId.toString(),
            "roleName" to roleName
        )

    is AuthorizationError.RoleNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Role not found", "roleName" to roleName)
}
