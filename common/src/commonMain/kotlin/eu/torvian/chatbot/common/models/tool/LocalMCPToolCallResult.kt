package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.Serializable

/**
 * Result of a local MCP tool execution.
 *
 * @property toolCallId Unique identifier for the tool call
 * @property output JSON output from the tool
 * @property isError Whether the tool execution resulted in an error
 * @property errorMessage Optional error message if isError is true
 */
@Serializable
data class LocalMCPToolCallResult(
    val toolCallId: Long,
    val output: String? = null,
    val isError: Boolean = false,
    val errorMessage: String? = null
)