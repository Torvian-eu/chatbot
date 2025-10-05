package eu.torvian.chatbot.common.models.api.llm

import eu.torvian.chatbot.common.models.LLMModelType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request body for adding a new LLM model configuration.
 *
 * @property name The unique identifier for the model (e.g., "gpt-3.5-turbo", "gpt-4").
 * @property providerId The ID of the provider that hosts this model.
 * @property type The operational type of this model (e.g., CHAT, EMBEDDING, etc.).
 * @property active Whether the model is currently active and available for use.
 * @property displayName Optional display name for UI purposes.
 * @property capabilities Optional JSON object containing model capabilities.
 */
@Serializable
data class AddModelRequest(
    val name: String,
    val providerId: Long,
    val type: LLMModelType,
    val active: Boolean = true,
    val displayName: String? = null,
    val capabilities: JsonObject? = null
)