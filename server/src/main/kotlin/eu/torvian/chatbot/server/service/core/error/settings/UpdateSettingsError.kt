package eu.torvian.chatbot.server.service.core.error.settings

/**
 * Represents possible errors when updating an existing settings profile.
 */
sealed interface UpdateSettingsError {
    /**
     * Indicates that the settings profile with the specified ID was not found.
     * Maps from SettingsError.SettingsNotFound in the DAO layer.
     */
    data class SettingsNotFound(val id: Long) : UpdateSettingsError

    /**
     * Indicates that the associated model for the settings profile was not found.
     * Maps from SettingsError.ModelNotFound in the DAO layer.
     */
    data class ModelNotFound(val modelId: Long) : UpdateSettingsError

    /**
     * Indicates invalid input data for the update (e.g., name blank, temperature out of range).
     * This would typically be a business rule validation failure.
     */
    data class InvalidInput(val reason: String) : UpdateSettingsError
}
