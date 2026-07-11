package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Result of a direct built-in tool execution.
 *
 * Mirrors the Local MCP result DTO so the orchestrator can uniformly translate worker output back
 * into a `ToolCall`. The structured result carries both human-readable text output and optional
 * machine-readable JSON details.
 *
 * @property output Textual output of the tool execution (when not an error).
 * @property details Optional structured JSON output (machine-readable).
 * @property isError Whether the tool execution resulted in an error.
 * @property errorMessage Optional human-readable error message when [isError] is `true`.
 * @property errorCode Optional machine-readable error code, used especially for worker-side authorization failures.
 * @property errorDetails Optional structured diagnostics suitable for logs or troubleshooting.
 */
@Serializable
data class BuiltInToolExecutionResult(
    val output: String? = null,
    val details: JsonObject? = null,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val errorCode: String? = null,
    val errorDetails: JsonObject? = null
)
