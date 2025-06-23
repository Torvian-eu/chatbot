package eu.torvian.chatbot.server.service.core.error.session

/**
 * Represents possible errors when deleting a chat session.
 */
sealed interface DeleteSessionError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : DeleteSessionError
}
