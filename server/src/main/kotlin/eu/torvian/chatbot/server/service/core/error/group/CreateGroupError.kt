package eu.torvian.chatbot.server.service.core.error.group

/**
 * Represents possible errors during the creation of a chat group.
 */
sealed interface CreateGroupError {
    /**
     * Indicates that the provided name is invalid (e.g., blank).
     */
    data class InvalidName(val reason: String) : CreateGroupError
}
