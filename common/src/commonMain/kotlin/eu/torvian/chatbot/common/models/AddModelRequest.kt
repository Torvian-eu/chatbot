package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for adding a new LLM model configuration.
 *
 * @property name The unique identifier for the model (e.g., "gpt-3.5-turbo", "gpt-4").
 * @property providerId The ID of the provider that hosts this model.
 * @property active Whether the model is currently active and available for use.
 * @property displayName Optional display name for UI purposes.
 */
@Serializable
data class AddModelRequest(
    val name: String,
    val providerId: Long,
    val active: Boolean = true,
    val displayName: String? = null
)