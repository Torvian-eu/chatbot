package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Represents a specific settings profile for an LLM model.
 * Allows configuring parameters like temperature, system message, etc.
 * Used as a shared data model between frontend and backend API communication.
 *
 * @property id Unique identifier for the settings profile (Database PK).
 * @property modelId Foreign key to the associated [LLMModel].
 * @property name The display name of the settings profile (e.g., "Default", "Creative").
 * @property systemMessage The system message/prompt to include in the conversation context.
 * @property temperature Sampling temperature for text generation.
 * @property maxTokens Maximum number of tokens to generate in the response.
 * @property customParamsJson Arbitrary model-specific parameters stored as a JSON string.
 */
@Serializable
data class ModelSettings(
    val id: Long,
    val modelId: Long,
    val name: String,
    val systemMessage: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val customParamsJson: String? = null
)