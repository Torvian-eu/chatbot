package eu.torvian.chatbot.common.models.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Represents a specific LLM model within a provider.
 * Used as a shared data model between frontend and backend API communication.
 *
 * A model is deliberately not assigned a single operational type: the same model can be used for
 * multiple purposes (Chat Completions, Responses API, embeddings, image generation, etc.). Which
 * purpose applies to a given invocation is decided by the [ModelSettings] profile attached to the
 * model, each of which carries its own [LLMModelType]. Model-level attributes that describe what a
 * model can do are expressed through [capabilities] instead.
 *
 * @property id Unique identifier for the model (Database PK)
 * @property name The model name (e.g., "gpt-3.5-turbo", "gpt-4", "claude-3-sonnet"). Not necessarily
 *            unique — the same name may be reused across settings profiles of different types.
 * @property providerId Reference to the LLM provider that hosts this model
 * @property active Whether the model can still be actively used (false for deprecated models)
 * @property displayName Optional display name for UI purposes (falls back to name if null)
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
    val capabilities: JsonObject? = null
)
