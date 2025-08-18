package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request body for adding a new settings profile for an LLM model.
 * Note: The model ID is part of the URL path for this endpoint.
 * @property name The display name of the settings profile.
 * @property systemMessage The system message/prompt (optional).
 * @property temperature Sampling temperature (optional).
 * @property maxTokens Maximum tokens (optional).
 * @property customParams Arbitrary model-specific parameters as [JsonObject] (optional).
 */
@Serializable
data class AddModelSettingsRequest(
    val name: String,
    val systemMessage: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val customParams: JsonObject? = null
)