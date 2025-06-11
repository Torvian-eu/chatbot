package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during ChatSession data operations.
 */
sealed interface SessionError {
    /**
     * Indicates that a session with the specified ID was not found.
     */
    data class SessionNotFound(val id: Long) : SessionError

    /**
     * Indicates that a foreign key constraint was violated. This is a generic error
     * that can occur when trying to use a non-existent ID for a related entity.
     */
    data class ForeignKeyViolation(val message: String) : SessionError
}
