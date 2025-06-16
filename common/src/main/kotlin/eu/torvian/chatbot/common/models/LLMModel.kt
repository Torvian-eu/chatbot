package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Represents a specific LLM model within a provider.
 * Used as a shared data model between frontend and backend API communication.
 *
 * @property id Unique identifier for the model (Database PK)
 * @property name Unique identifier for the LLM model (e.g., "gpt-3.5-turbo", "gpt-4", "claude-3-sonnet")
 * @property providerId Reference to the LLM provider that hosts this model
 * @property active Whether the model can still be actively used (false for deprecated models)
 * @property displayName Optional display name for UI purposes (falls back to name if null)
 */
@Serializable
data class LLMModel(
    val id: Long,
    val name: String,
    val providerId: Long,
    val active: Boolean,
    val displayName: String? = null
)