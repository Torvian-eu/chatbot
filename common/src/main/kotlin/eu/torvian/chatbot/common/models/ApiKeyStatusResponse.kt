package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Response body for checking API key configuration status for a model.
 *
 * @property isConfigured True if an API key is securely stored for this model, false otherwise.
 */
@Serializable
data class ApiKeyStatusResponse(
    val isConfigured: Boolean
)