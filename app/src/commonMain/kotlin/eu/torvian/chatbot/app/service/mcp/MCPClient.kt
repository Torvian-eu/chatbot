package eu.torvian.chatbot.app.service.mcp

import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import kotlinx.datetime.Instant

/**
 * Public representation of an active MCP client connection.
 *
 * This class is platform-independent and contains metadata about a
 * running client connection.
 *
 * @property serverConfig The full LocalMCPServer configuration
 * @property processStatus The last known process status from the ProcessManager
 * @property connectedAt When the client was connected
 * @property isResponsive Result of a recent ping check
 */
data class MCPClient(
    val serverConfig: LocalMCPServer,
    val processStatus: ProcessStatus,
    val connectedAt: Instant,
    val isResponsive: Boolean,
)
