package eu.torvian.chatbot.server.data.dao.error

/**
 * Error types for tool definition DAO operations.
 */
sealed class ToolDefinitionError {
    /**
     * Indicates that a tool definition with the specified ID was not found.
     *
     * @property id The ID that was not found
     */
    data class NotFound(val id: Long) : ToolDefinitionError()

}
