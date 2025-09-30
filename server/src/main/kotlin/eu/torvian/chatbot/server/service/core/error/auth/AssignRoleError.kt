package eu.torvian.chatbot.server.service.core.error.auth

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

