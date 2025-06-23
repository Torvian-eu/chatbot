package eu.torvian.chatbot.server.service.core.error.provider

/**
 * Represents possible errors when updating an existing LLM provider.
 */
sealed interface UpdateProviderError {
    /**
     * Indicates that the provider with the specified ID was not found.
     */
    data class ProviderNotFound(val id: Long) : UpdateProviderError
    
    /**
     * Indicates invalid input data for the update (e.g., name, description, or baseUrl format).
     * This would typically be a business rule validation failure.
     */
    data class InvalidInput(val reason: String) : UpdateProviderError
    
    /**
     * Indicates that a provider with the specified API key already exists (and it's not the same provider being updated).
     */
    data class ApiKeyAlreadyInUse(val apiKeyId: String) : UpdateProviderError
}
