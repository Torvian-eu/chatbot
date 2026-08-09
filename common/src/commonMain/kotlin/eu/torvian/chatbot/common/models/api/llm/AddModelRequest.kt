package eu.torvian.chatbot.common.models.api.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request body for adding a new LLM model configuration.
 *
 * A model carries no operational type of its own; the purposes it can serve are expressed through
 * the settings profiles attached to it (each of which has its own [eu.torvian.chatbot.common.models.llm.LLMModelType])
 * and through its capabilities.
 *
 * @property name The unique identifier for the model (e.g., "gpt-3.5-turbo", "gpt-4").
 * @property providerId The ID of the provider that hosts this model.
 * @property active Whether the model is currently active and available for use.
 * @property displayName Optional display name for UI purposes.
 * @property capabilities Optional JSON object containing model capabilities.
 */
@Serializable
data class AddModelRequest(
    val name: String,
    val providerId: Long,
    val active: Boolean = true,
    val displayName: String? = null,
    val capabilities: JsonObject? = null
)