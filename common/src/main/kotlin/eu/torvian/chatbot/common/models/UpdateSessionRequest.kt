package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for updating chat session details.
 * Allows updating fields like name, current model, and settings.
 * Group assignment uses a dedicated endpoint and request body.
 * Note: Fields are nullable to allow partial updates.
 *
 * @property name New name for the session (optional).
 * @property currentModelId New selected model ID for the session (optional).
 * @property currentSettingsId New selected settings ID for the session (optional).
 */
@Serializable
data class UpdateSessionRequest(
    val name: String? = null,
    val currentModelId: Long? = null,
    val currentSettingsId: Long? = null
)