package eu.torvian.chatbot.server.service.core.error.session
/**
 * Represents possible errors when updating a chat session's current settings ID.
 */
sealed interface UpdateSessionCurrentSettingsIdError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionCurrentSettingsIdError
    /**
     * Indicates that the provided settings ID was not found.
     * Maps from SessionError.ForeignKeyViolation in the DAO layer.
     */
    data class InvalidRelatedEntity(val message: String) : UpdateSessionCurrentSettingsIdError
}
