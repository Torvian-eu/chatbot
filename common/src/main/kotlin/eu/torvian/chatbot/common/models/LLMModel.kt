package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Represents a configured LLM model endpoint.
 * Used as a shared data model between frontend and backend API communication.
 *
 * @property id Unique identifier for the model (Database PK).
 * @property name The display name of the model.
 * @property baseUrl The base URL for the LLM API endpoint (e.g., "https://api.openai.com").
 * @property apiKeyId Reference ID to the securely stored API key (null if not required or configured).
 *                   The raw API key is NOT exposed in this model for security. Supports secure key handling (V1.1).
 * @property type The type of LLM provider (e.g., "openai", "openrouter", "custom").
 */
@Serializable
data class LLMModel(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val apiKeyId: String?,
    val type: String
)