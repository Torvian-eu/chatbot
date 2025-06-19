package eu.torvian.chatbot.server.service.core.error.provider

/**
 * Represents possible errors when deleting an LLM provider.
 */
sealed interface DeleteProviderError {
    /**
     * Indicates that the provider with the specified ID was not found.
     */
    data class ProviderNotFound(val id: Long) : DeleteProviderError
    
    /**
     * Indicates that the provider cannot be deleted because it is still in use.
     * This occurs when models are still referencing this provider.
     */
    data class ProviderInUse(val id: Long, val modelNames: List<String>) : DeleteProviderError
}
