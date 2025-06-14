package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during ChatMessage data operations,
 * specifically those common to multiple operations.
 */
sealed interface MessageError {
    /**
     * Indicates that a message with the specified ID was not found.
     */
    data class MessageNotFound(val id: Long) : MessageError

    /**
     * Indicates that a foreign key constraint was violated during an insert or update.
     * This is a generic error for such constraint failures.
     */
    data class ForeignKeyViolation(val message: String) : MessageError
}