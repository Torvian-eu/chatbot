package eu.torvian.chatbot.common.models.api.tool

import kotlinx.serialization.Serializable

/**
 * Request body for batch enabling or disabling multiple tools for a specific session.
 *
 * @property toolIds List of tool IDs to enable or disable
 * @property enabled Whether to enable (true) or disable (false) the tools for this session
 */
@Serializable
data class SetToolsEnabledRequest(
    val toolIds: List<Long>,
    val enabled: Boolean
)

