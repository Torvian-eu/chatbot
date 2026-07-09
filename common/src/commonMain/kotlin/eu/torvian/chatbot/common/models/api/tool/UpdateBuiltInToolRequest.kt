package eu.torvian.chatbot.common.models.api.tool

import kotlinx.serialization.Serializable

/**
 * Lightweight request payload for updating a built-in worker tool definition's enabled state.
 *
 * This DTO is used by `PUT /api/v1/built-in-tools/{toolId}` to toggle the
 * global `isEnabled` flag on a built-in tool.
 *
 * @property isEnabled Whether the built-in tool should be globally enabled.
 */
@Serializable
data class UpdateBuiltInToolRequest(
    val isEnabled: Boolean
)
