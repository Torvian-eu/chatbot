package eu.torvian.chatbot.server.service.core.error.session
/**
 * Represents possible errors when updating a chat session's current model ID.
 */
sealed interface UpdateSessionCurrentModelIdError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionCurrentModelIdError
    /**
     * Indicates that the provided model ID was not found.
     * Maps from SessionError.ForeignKeyViolation in the DAO layer.
     */
    data class InvalidRelatedEntity(val message: String) : UpdateSessionCurrentModelIdError
}
