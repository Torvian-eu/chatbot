package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during ModelSettings data operations.
 */
sealed interface SettingsError {
    /**
     * Indicates that a settings profile with the specified ID was not found.
     */
    data class SettingsNotFound(val id: Long) : SettingsError

    /**
     * Indicates that the associated model for the settings profile was not found.
     */
    data class ModelNotFound(val modelId: Long) : SettingsError
}