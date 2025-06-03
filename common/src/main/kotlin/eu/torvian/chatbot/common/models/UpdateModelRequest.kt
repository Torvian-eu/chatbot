package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for updating an existing LLM model configuration.
 * Allows updating various model details and optionally providing a new API key.
 * Note: Fields are nullable to allow partial updates.
 *
 * @property id The ID of the model being updated. Required.
 * @property name New display name (optional).
 * @property baseUrl New base URL (optional).
 * @property type New type (optional).
 * @property apiKey Provide a new raw API key string here to update the stored key (optional). Omit or send null/empty string to keep the existing key.
 */
@Serializable
data class UpdateModelRequest(
    val id: Long,
    val name: String? = null,
    val baseUrl: String? = null,
    val type: String? = null,
    val apiKey: String? = null
)