package eu.torvian.chatbot.server.service.core.error.message

/**
 * Represents possible errors when updating the content of a message.
 */
sealed interface UpdateMessageContentError {
    /**
     * Indicates that the message with the specified ID was not found.
     * Maps from MessageError.MessageNotFound in the DAO layer.
     */
    data class MessageNotFound(val id: Long) : UpdateMessageContentError
}
