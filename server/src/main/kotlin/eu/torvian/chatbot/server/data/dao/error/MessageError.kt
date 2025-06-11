package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during ChatMessage data operations.
 */
sealed interface MessageError {
    /**
     * Indicates that a message with the specified ID was not found.
     */
    data class MessageNotFound(val id: Long) : MessageError

    /**
     * Indicates that a foreign key constraint was violated. This is a generic error
     * that can occur when trying to use a non-existent ID for a related entity.
     */
    data class ForeignKeyViolation(val message: String) : MessageError

    /**
     * Indicates that a child message already exists for the parent message.
     */
    data class ChildAlreadyExists(val parentId: Long, val childId: Long) : MessageError

    /**
     * Indicates that a child message was not found for the parent message.
     */
    data class ChildNotFound(val parentId: Long, val childId: Long) : MessageError
}