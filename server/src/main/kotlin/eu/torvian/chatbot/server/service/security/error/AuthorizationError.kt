package eu.torvian.chatbot.server.service.security.error

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
