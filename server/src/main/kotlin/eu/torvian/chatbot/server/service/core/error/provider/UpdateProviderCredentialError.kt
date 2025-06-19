package eu.torvian.chatbot.server.service.core.error.provider

/**
 * Represents possible errors when updating an LLM provider's credential.
 */
sealed interface UpdateProviderCredentialError {
    /**
     * Indicates that the provider with the specified ID was not found.
     */
    data class ProviderNotFound(val id: Long) : UpdateProviderCredentialError
    
    /**
     * Indicates invalid input data for the update (e.g., credential format).
     * This would typically be a business rule validation failure.
     */
    data class InvalidInput(val reason: String) : UpdateProviderCredentialError
}
