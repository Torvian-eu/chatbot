package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.Serializable

/**
 * Response payload for runtime connection checks against a Local MCP server.
 *
 * The `serverId` is present for persisted-server calls and omitted for draft requests, so the
 * same contract can cover both connection-test flows without duplicating the payload shape.
 *
 * @property serverId Identifier of the server that was tested, or null for draft requests.
 * @property success Whether the runtime reported a successful connectivity check.
 * @property discoveredToolCount Number of tools observed during the test operation.
 * @property message Optional human-readable details about the runtime outcome.
 */
@Serializable
data class TestLocalMCPServerConnectionResponse(
    val serverId: Long? = null,
    val success: Boolean,
    val discoveredToolCount: Int,
    val message: String? = null
)

