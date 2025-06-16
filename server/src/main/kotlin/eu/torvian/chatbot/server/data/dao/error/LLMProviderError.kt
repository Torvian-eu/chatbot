package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during LLMProvider data operations.
 */
sealed interface LLMProviderError {
    /**
     * Indicates that an LLM provider with the specified ID was not found.
     */
    data class LLMProviderNotFound(val id: Long) : LLMProviderError

    /**
     * Indicates that an LLM provider with the specified API key ID already exists.
     */
    data class ApiKeyAlreadyInUse(val apiKeyId: String) : LLMProviderError
}
