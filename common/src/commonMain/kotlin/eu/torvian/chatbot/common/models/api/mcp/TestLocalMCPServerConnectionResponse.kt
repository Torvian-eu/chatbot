package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.Serializable

/**
 * Response payload for runtime connection checks against a persisted Local MCP server.
 *
 * This payload intentionally stays generic so it can be returned by a temporary dummy
 * implementation today and a worker-backed implementation later without changing the API
 * contract used by the app.
 *
 * @property serverId Identifier of the server that was tested.
 * @property success Whether the runtime reported a successful connectivity check.
 * @property discoveredToolCount Number of tools observed during the test operation.
 * @property message Optional human-readable details about the runtime outcome.
 */
@Serializable
data class TestLocalMCPServerConnectionResponse(
    val serverId: Long,
    val success: Boolean,
    val discoveredToolCount: Int,
    val message: String? = null
)

