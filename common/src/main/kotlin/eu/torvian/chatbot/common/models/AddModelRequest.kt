package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for adding a new LLM model configuration.
 * Includes the raw API key which is handled securely by the backend.
 *
 * @property name The display name of the model.
 * @property baseUrl The base URL for the LLM API endpoint.
 * @property type The type of LLM provider.
 * @property apiKey The raw API key provided by the user. Passed once for secure storage by the backend.
 */
@Serializable
data class AddModelRequest(
    val name: String,
    val baseUrl: String,
    val type: String,
    val apiKey: String? = null
)