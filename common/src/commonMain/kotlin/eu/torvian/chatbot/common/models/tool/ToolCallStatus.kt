package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.Serializable

/**
 * Enumeration of tool call execution statuses.
 *
 * Represents the current state of a tool call in its lifecycle from request to completion.
 */
@Serializable
enum class ToolCallStatus {
    /** Tool call is queued or in progress */
    PENDING,

    /** Tool executed successfully */
    SUCCESS,

    /** Tool execution failed */
    ERROR
}

