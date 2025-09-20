package eu.torvian.chatbot.server.service.core.error.message

/**
 * Errors that can occur when retrieving messages by session ID.
 */
sealed class GetMessagesError {
    /**
     * The session with the given ID was not found.
     */
    data class SessionNotFound(val sessionId: Long) : GetMessagesError()

    /**
     * The user does not have access to the session.
     */
    data class AccessDenied(val message: String) : GetMessagesError()
}
