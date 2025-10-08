package eu.torvian.chatbot.server.service.core.error.provider

/**
 * Represents possible errors when adding a new LLM provider.
 */
sealed interface AddProviderError {
    /**
     * Indicates invalid input data for the provider (e.g., name, description, or baseUrl format).
     * This would typically be a business rule validation failure.
     */
    data class InvalidInput(val reason: String) : AddProviderError

    /**
     * Indicates that there was an error setting ownership for the provider.
     */
    data class OwnershipError(val reason: String) : AddProviderError
}
