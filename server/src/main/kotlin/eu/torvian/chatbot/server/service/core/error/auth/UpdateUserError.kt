package eu.torvian.chatbot.server.service.core.error.auth

/**
 * Sealed interface representing errors that can occur during user profile updates.
 */
sealed interface UpdateUserError {
    /**
     * User with the specified ID was not found.
     *
     * @property userId The ID of the user that was not found
     */
    data class UserNotFound(val userId: Long) : UpdateUserError

    /**
     * Username already exists in the system.
     *
     * @property username The username that already exists
     */
    data class UsernameAlreadyExists(val username: String) : UpdateUserError

    /**
     * Email address already exists in the system.
     *
     * @property email The email that already exists
     */
    data class EmailAlreadyExists(val email: String) : UpdateUserError

    /**
     * Invalid input provided during update.
     *
     * @property reason Description of what input was invalid
     */
    data class InvalidInput(val reason: String) : UpdateUserError
}

