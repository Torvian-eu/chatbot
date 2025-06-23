package eu.torvian.chatbot.server.service.core.error.provider

/**
 * Represents possible errors when retrieving an LLM provider.
 */
sealed interface GetProviderError {
    /**
     * Indicates that the provider with the specified ID was not found.
     */
    data class ProviderNotFound(val id: Long) : GetProviderError
}
