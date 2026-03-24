package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.Serializable

/**
 * Response body for updating the current model ID of a chat session.
 *
 * @property currentModelId The resulting current model ID for the session.
 * @property currentSettingsId The resulting current settings ID for the session.
 */
@Serializable
data class UpdateSessionModelResponse(
    val currentModelId: Long?,
    val currentSettingsId: Long?
)

