package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request payload for one direct (non-MCP) built-in tool execution on a worker.
 *
 * The worker resolves the public tool name (e.g. `project1.read_text_file`) against its in-memory
 * built-in tool registry and executes the matching implementation inside the worker's `workspace`.
 *
 * @property toolName Public tool name (optionally prefixed) to invoke on the worker.
 * @property input JSON argument object for the tool.
 */
@Serializable
data class BuiltInToolExecutionRequest(
    val toolName: String,
    val input: JsonObject
)

