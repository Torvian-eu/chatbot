package eu.torvian.chatbot.server.service.core.error.session

/**
 * Represents possible errors when updating a chat session's current model ID and settings ID atomically.
 * This covers all error scenarios that can occur during the combined operation.
 */
sealed interface UpdateSessionCurrentModelAndSettingsIdError {
    /**
     * Indicates that the session with the specified ID was not found.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionCurrentModelAndSettingsIdError

    /**
     * Indicates that the provided model ID was not found or is invalid.
     */
    data class ModelNotFound(val modelId: Long) : UpdateSessionCurrentModelAndSettingsIdError

    /**
     * Indicates that the provided settings ID was not found.
     */
    data class SettingsNotFound(val settingsId: Long) : UpdateSessionCurrentModelAndSettingsIdError

    /**
     * Indicates that the provided settings are not compatible with the specified model.
     * This occurs when trying to assign settings that belong to a different model.
     */
    data class SettingsModelMismatch(
        val settingsId: Long,
        val settingsModelId: Long,
        val providedModelId: Long
    ) : UpdateSessionCurrentModelAndSettingsIdError

    /**
     * Indicates a foreign key constraint violation or other database-related validation error.
     */
    data class InvalidRelatedEntity(val message: String) : UpdateSessionCurrentModelAndSettingsIdError

    /**
     * Indicates that the provided settings are not of the ChatModelSettings type.
     * This occurs when trying to assign settings that are not suitable for chat sessions.
     */
    data class InvalidSettingsType(val settingsId: Long, val actualType: String) : UpdateSessionCurrentModelAndSettingsIdError
}
