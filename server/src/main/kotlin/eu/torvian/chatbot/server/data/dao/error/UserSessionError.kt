package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during UserSession data operations.
 */
sealed interface UserSessionError {
    /**
     * Indicates that a user session with the specified ID was not found.
     */
    data class SessionNotFound(val id: Long) : UserSessionError

    /**
     * Indicates that a foreign key constraint was violated during session creation.
     * This typically occurs when referencing a non-existent user ID.
     */
    data class ForeignKeyViolation(val message: String) : UserSessionError
}
