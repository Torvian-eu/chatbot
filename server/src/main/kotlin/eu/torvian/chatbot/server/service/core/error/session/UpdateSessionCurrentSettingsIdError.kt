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
    /**
     * Indicates that the provided settings are not compatible with the session's current model.
     * This occurs when trying to assign settings that belong to a different model.
     */
    data class SettingsModelMismatch(val settingsId: Long, val settingsModelId: Long, val sessionModelId: Long?) : UpdateSessionCurrentSettingsIdError
    /**
     * Indicates that the provided settings are not of the ChatModelSettings type.
     * This occurs when trying to assign settings that are not suitable for chat sessions.
     */
    data class InvalidSettingsType(val settingsId: Long, val actualType: String) : UpdateSessionCurrentSettingsIdError
    /**
     * Indicates that the user does not have permission to update this session.
     */
    data class AccessDenied(val message: String) : UpdateSessionCurrentSettingsIdError
}
