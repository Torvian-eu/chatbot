package eu.torvian.chatbot.common.models.api.llm

import eu.torvian.chatbot.common.models.LLMProviderType
import kotlinx.serialization.Serializable

/**
 * Request body for adding a new LLM provider configuration.
 *
 * @property name The display name for the provider (e.g., "OpenAI Production", "Ollama Local").
 * @property description Optional description providing additional context about the provider.
 * @property baseUrl The base URL for the LLM API endpoint (e.g., "https://api.openai.com", "http://localhost:11434").
 * @property type The type of LLM provider.
 * @property credential The API key credential to store securely (null for local providers like Ollama).
 */
@Serializable
data class AddProviderRequest(
    val name: String,
    val description: String,
    val baseUrl: String,
    val type: LLMProviderType,
    val credential: String? = null
)
