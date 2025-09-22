package eu.torvian.chatbot.server.service.core.error.message

/**
 * Represents possible errors when deleting a message.
 */
sealed interface DeleteMessageError {
    /**
     * Indicates that the message with the specified ID was not found.
     * Maps from MessageError.MessageNotFound in the DAO layer.
     */
    data class MessageNotFound(val id: Long) : DeleteMessageError

    /**
     * Indicates that the user does not have permission to delete this message.
     */
    data class AccessDenied(val reason: String) : DeleteMessageError

    /**
     * Indicates that updating the session's leaf message ID failed after successful message deletion.
     * This ensures the session state remains consistent even if the deletion succeeded.
     */
    data class SessionUpdateFailed(val sessionId: Long) : DeleteMessageError
}
