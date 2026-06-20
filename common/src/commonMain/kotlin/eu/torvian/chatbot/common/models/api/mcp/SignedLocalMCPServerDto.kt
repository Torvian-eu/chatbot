package eu.torvian.chatbot.common.models.api.mcp

import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.Serializable

/**
 * Worker-facing transport wrapper for a Local MCP server and its detached authorization snapshot.
 *
 * The server may relay persisted configurations, but the worker still verifies the detached request
 * locally before accepting executable state. When [signedRequest] is absent, the worker must reject
 * the config instead of trusting the relayed DTO.
 *
 * @property server Persisted Local MCP server configuration relayed to the worker.
 * @property signedRequest Detached signed request snapshot associated with the current persisted state,
 *   when available.
 */
@Serializable
data class SignedLocalMCPServerDto(
    val server: LocalMCPServerDto,
    val signedRequest: SignedRequest? = null
)
