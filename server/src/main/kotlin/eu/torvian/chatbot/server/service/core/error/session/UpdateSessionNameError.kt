package eu.torvian.chatbot.server.service.core.error.session
/**
 * Represents possible errors when updating a chat session's name.
 */
sealed interface UpdateSessionNameError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionNameError
    /**
     * Indicates that the provided new name is invalid (e.g., blank).
     */
    data class InvalidName(val reason: String) : UpdateSessionNameError

    /**
     * Indicates that the user does not have access to update the requested session.
     */
    data class AccessDenied(val reason: String) : UpdateSessionNameError
}
