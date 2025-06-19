package eu.torvian.chatbot.server.service.core.error.settings

/**
 * Represents possible errors when deleting a settings profile.
 */
sealed interface DeleteSettingsError {
    /**
     * Indicates that the settings profile with the specified ID was not found.
     * Maps from SettingsError.SettingsNotFound in the DAO layer.
     */
    data class SettingsNotFound(val id: Long) : DeleteSettingsError
}
