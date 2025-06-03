package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for updating an existing settings profile.
 * Allows updating parameters for a settings profile. Note: Fields are nullable for partial updates.
 *
 * @property id The ID of the settings profile being updated. Required.
 * @property name New display name (optional).
 * @property systemMessage New system message (optional).
 * @property temperature New temperature (optional).
 * @property maxTokens New max tokens (optional).
 * @property customParamsJson New custom parameters as JSON string (optional).
 */
@Serializable
data class UpdateSettingsRequest(
    val id: Long,
    val name: String? = null,
    val systemMessage: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val customParamsJson: String? = null
)