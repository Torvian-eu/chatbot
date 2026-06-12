package eu.torvian.chatbot.common.models.api.mcp

import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.Serializable

/**
 * Minimal protocol payload carrying only a detached signed request for Local MCP tool execution.
 *
 * The signed request payload contains [LocalMCPToolExecutionAuthorization], which the worker
 * decodes and validates.
 *
 * @property signedRequest Detached signature metadata and the exact authorization payload signed by the app.
 */
@Serializable
data class SignedLocalMCPToolExecutionRequest(
    val signedRequest: SignedRequest
)
