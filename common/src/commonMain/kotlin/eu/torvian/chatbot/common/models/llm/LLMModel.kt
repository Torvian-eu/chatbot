package eu.torvian.chatbot.common.models.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Represents a specific LLM model within a provider.
 * Used as a shared data model between frontend and backend API communication.
 *
 * @property id Unique identifier for the model (Database PK)
 * @property name Unique identifier for the LLM model (e.g., "gpt-3.5-turbo", "gpt-4", "claude-3-sonnet")
 * @property providerId Reference to the LLM provider that hosts this model
 * @property active Whether the model can still be actively used (false for deprecated models)
 * @property displayName Optional display name for UI purposes (falls back to name if null)
 * @property type The operational [LLMModelType] of this model, indicating its primary function
 *                  and expected API interaction.
 * @property capabilities An optional [JsonObject] containing a map of model capabilities.
 *                            Keys are capability names (e.g., "TOOL_CALLING"), and values can be
 *                            booleans for simple flags, or JSON objects/arrays for more complex,
 *                            parameterized capabilities.
 *                            Use the extension functions on [LLMModel] for convenient querying.
 */
@Serializable
data class LLMModel(
    val id: Long,
    val name: String,
    val providerId: Long,
    val active: Boolean,
    val displayName: String? = null,
    val type: LLMModelType,
    val capabilities: JsonObject? = null
)
