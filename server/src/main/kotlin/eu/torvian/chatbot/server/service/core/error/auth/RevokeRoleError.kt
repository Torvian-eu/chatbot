package eu.torvian.chatbot.server.service.core.error.auth

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

