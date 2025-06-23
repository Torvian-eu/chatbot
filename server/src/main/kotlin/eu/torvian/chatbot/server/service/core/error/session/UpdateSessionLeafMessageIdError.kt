package eu.torvian.chatbot.server.service.core.error.session
/**
 * Represents possible errors when updating a chat session's current leaf message ID.
 */
sealed interface UpdateSessionLeafMessageIdError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionLeafMessageIdError
    /**
     * Indicates that the provided message ID was not found.
     * Maps from SessionError.ForeignKeyViolation in the DAO layer.
     */
    data class InvalidRelatedEntity(val message: String) : UpdateSessionLeafMessageIdError
}
