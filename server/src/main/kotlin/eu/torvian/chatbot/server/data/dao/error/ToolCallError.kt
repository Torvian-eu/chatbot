package eu.torvian.chatbot.server.data.dao.error

/**
 * Error types for tool call retrieval operations.
 */
sealed class ToolCallError {
    /**
     * Indicates that a tool call with the specified ID was not found.
     *
     * @property id The ID that was not found
     */
    data class NotFound(val id: Long) : ToolCallError()
}

/**
 * Error types for tool call insertion operations.
 */
sealed class InsertToolCallError {
    /**
     * Indicates that a foreign key constraint was violated during tool call insertion.
     * This typically occurs when referencing a non-existent message ID or tool definition ID.
     *
     * @property message A description of which constraint was violated
     */
    data class ForeignKeyViolation(val message: String) : InsertToolCallError()
}

/**
 * Error types for tool call update operations.
 */
sealed class UpdateToolCallError {
    /**
     * Indicates that the tool call to update was not found.
     *
     * @property id The ID that was not found
     */
    data class NotFound(val id: Long) : UpdateToolCallError()
}

/**
 * Error types for tool call deletion operations.
 */
sealed class DeleteToolCallError {
    /**
     * Indicates that no tool calls were found for the specified message.
     *
     * @property messageId The message ID for which no tool calls were found
     */
    data class NotFound(val messageId: Long) : DeleteToolCallError()
}

