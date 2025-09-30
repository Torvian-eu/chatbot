package eu.torvian.chatbot.server.service.core.error.auth

/**
 * Sealed interface representing errors that can occur during password changes.
 */
sealed interface ChangePasswordError {
    /**
     * User with the specified ID was not found.
     *
     * @property userId The ID of the user that was not found
     */
    data class UserNotFound(val userId: Long) : ChangePasswordError

    /**
     * Password does not meet strength requirements.
     *
     * @property reason Description of why the password is invalid
     */
    data class InvalidPassword(val reason: String) : ChangePasswordError
}

