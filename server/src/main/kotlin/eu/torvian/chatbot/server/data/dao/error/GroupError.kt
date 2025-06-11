package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during ChatGroup data operations.
 */
sealed interface GroupError {
    /**
     * Indicates that a group with the specified ID was not found.
     */
    data class GroupNotFound(val id: Long) : GroupError
}