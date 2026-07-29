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
 * @property output Hybrid string containing textual output of the tool execution (when not an error).
 *                 This field is **hybrid**: it may be a JSON string (e.g. from `read_text_file`
 *                 returning file contents) or plain text (e.g. from `run_command` returning stdout).
 * @property details Optional structured JSON output (machine-readable).
 * @property isError Whether the tool execution resulted in an error.
 * @property errorMessage Optional human-readable error message when [isError] is `true`.
 * @property errorCode Optional machine-readable error code, used especially for worker-side authorization failures.
 * @property errorDetails Hybrid string containing optional structured diagnostics when [isError] is `true`.
 *                       This field is **hybrid**: it may be a JSON string (e.g. from `run_command`
 *                       with accumulated validation errors) or a plain text string. Consumers should
 *                       parse it as JSON when appropriate.
 */
@Serializable
data class BuiltInToolExecutionResult(
    val output: String? = null,
    val details: JsonObject? = null,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val errorCode: String? = null,
    val errorDetails: String? = null
)