package eu.torvian.chatbot.common.models.llm

import kotlinx.serialization.Serializable

/**
 * Represents an LLM provider configuration in the chatbot system.
 * 
 * This model represents a configured LLM provider endpoint with its associated API key.
 * Multiple providers can use the same underlying service (e.g., multiple OpenAI configurations
 * with different API keys or base URLs).
 *
 * @property id Unique identifier for the provider (Database PK)
 * @property apiKeyId Reference ID to the securely stored API key for this provider (null for local providers like Ollama)
 * @property name The display name for the provider (e.g., "OpenAI Production", "Ollama Local")
 * @property description Optional description providing additional context about the provider
 * @property baseUrl The base URL for the LLM API endpoint (e.g., "https://api.openai.com", "http://localhost:11434")
 * @property type The type of LLM provider
 */
@Serializable
data class LLMProvider(
    val id: Long,
    val apiKeyId: String?,
    val name: String,
    val description: String,
    val baseUrl: String,
    val type: LLMProviderType
)
