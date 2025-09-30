package eu.torvian.chatbot.server.service.core.error.auth

/**
 * Sealed interface representing errors that can occur during user deletion.
 */
sealed interface DeleteUserError {
    /**
     * User with the specified ID was not found.
     *
     * @property userId The ID of the user that was not found
     */
    data class UserNotFound(val userId: Long) : DeleteUserError

    /**
     * Cannot delete the last administrator in the system.
     *
     * @property userId The ID of the last admin user
     */
    data class CannotDeleteLastAdmin(val userId: Long) : DeleteUserError
}

