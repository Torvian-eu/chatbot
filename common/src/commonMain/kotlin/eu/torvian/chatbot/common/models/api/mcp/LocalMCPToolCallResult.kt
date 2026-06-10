package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.Serializable

/**
 * Result of a local MCP tool execution.
 *
 * @property toolCallId Unique identifier for the tool call.
 * @property output JSON output from the tool.
 * @property isError Whether the tool execution resulted in an error.
 * @property errorMessage Optional human-readable error message when [isError] is `true`.
 * @property errorCode Optional machine-readable error code, used especially for worker-side authorization failures.
 * @property errorDetails Optional structured diagnostics suitable for logs or troubleshooting.
 */
@Serializable
data class LocalMCPToolCallResult(
    val toolCallId: Long,
    val output: String? = null,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val errorCode: String? = null,
    val errorDetails: String? = null
)