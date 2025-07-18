package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for adding a new settings profile for an LLM model.
 * Note: The model ID is part of the URL path for this endpoint.
 * @property name The display name of the settings profile.
 * @property systemMessage The system message/prompt (optional).
 * @property temperature Sampling temperature (optional).
 * @property maxTokens Maximum tokens (optional).
 * @property customParamsJson Arbitrary model-specific parameters as JSON string (optional).
 */
@Serializable
data class AddModelSettingsRequest(
    val name: String,
    val systemMessage: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val customParamsJson: String? = null
)