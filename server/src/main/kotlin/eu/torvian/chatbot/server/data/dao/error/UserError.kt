package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during User data operations.
 */
sealed interface UserError {
    /**
     * Indicates that a user with the specified ID was not found.
     */
    data class UserNotFound(val id: Long) : UserError

    /**
     * Indicates that a user with the specified username was not found.
     */
    data class UserNotFoundByUsername(val username: String) : UserError

    /**
     * Indicates that a username is already taken by another user.
     */
    data class UsernameAlreadyExists(val username: String) : UserError

    /**
     * Indicates that an email address is already taken by another user.
     */
    data class EmailAlreadyExists(val email: String) : UserError
}
