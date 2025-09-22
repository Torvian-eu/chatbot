package eu.torvian.chatbot.server.service.core.error.session

/**
 * Represents possible errors when retrieving detailed information for a chat session.
 */
sealed interface GetSessionDetailsError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : GetSessionDetailsError

    /**
     * Indicates that the user does not have access to the requested session.
     */
    data class AccessDenied(val reason: String) : GetSessionDetailsError
}
