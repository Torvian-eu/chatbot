package eu.torvian.chatbot.server.service.core.error.settings

/**
 * Represents possible errors when adding a new settings profile.
 */
sealed interface AddSettingsError {
     /**
      * Indicates that the associated model for the settings profile was not found.
      * Maps from SettingsError.ModelNotFound in the DAO layer.
      */
     data class ModelNotFound(val modelId: Long) : AddSettingsError
     /**
      * Indicates invalid input data for the settings (e.g., name blank, temperature out of range).
      * This would typically be a business rule validation failure.
      */
     data class InvalidInput(val reason: String) : AddSettingsError
     /**
      * Indicates an ownership-related failure when setting the owner for a newly created settings profile.
      */
     data class OwnershipError(val reason: String) : AddSettingsError
}
