package eu.torvian.chatbot.app.service.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import kotlin.time.Instant

/**
 * Public representation of an active MCP client connection.
 *
 * This class is platform-independent and contains metadata about a
 * running client connection.
 *
 * @property serverConfig The full LocalMCPServerDto configuration
 * @property processStatus The last known process status from the ProcessManager
 * @property connectedAt When the client was connected
 * @property lastActivityAt Timestamp of the last operation on this connection
 * @property isResponsive Result of a recent ping check
 */
data class MCPClient(
    val serverConfig: LocalMCPServerDto,
    val processStatus: ProcessStatus,
    val connectedAt: Instant,
    val lastActivityAt: Instant,
    val isResponsive: Boolean,
)
