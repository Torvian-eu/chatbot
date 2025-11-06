package eu.torvian.chatbot.common.models.api.tool

import kotlinx.serialization.Serializable

/**
 * Request body for enabling or disabling a tool for a specific session.
 *
 * @property enabled Whether to enable (true) or disable (false) the tool for this session
 */
@Serializable
data class SetToolEnabledRequest(
    val enabled: Boolean
)

