package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request payload for one direct (non-MCP) built-in tool execution on a worker.
 *
 * The worker resolves the tool name against its in-memory built-in tool registry by the
 * unprefixed tool name (e.g. `read_text_file`). Tool-name prefixing is a server-side catalog
 * concern and is not applied at the worker runtime.
 *
 * Note: This DTO is retained for planned protocol evolution (diagnostic echo flows and
 * server-side request introspection). The current runtime dispatch uses
 * [SignedBuiltInToolExecutionRequest] which wraps a [SignedRequest] containing the
 * authorization envelope; this bare request type is not used in the active dispatch path.
 *
 * @property toolName Tool name (unprefixed) to invoke on the worker.
 * @property input JSON argument object for the tool.
 */
@Serializable
data class BuiltInToolExecutionRequest(
    val toolName: String,
    val input: JsonObject
)
