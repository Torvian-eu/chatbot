package eu.torvian.chatbot.app.service.mcp

import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import kotlin.time.Instant

/**
 * Data class representing the aggregate status of a Local MCP Server.
 *
 * @property serverConfig The full LocalMCPServer configuration
 * @property tools List of tools discovered from the server (null if not discovered)
 * @property isConnected Whether the server is currently connected
 * @property processStatus The last known process status from the ProcessManager
 * @property connectedAt When the client was connected
 * @property lastActivityAt Timestamp of the last operation on this connection
 * @property isResponsive Result of a recent ping check
 */
data class LocalMCPServerOverview(
    val serverConfig: LocalMCPServer,
    val tools: List<LocalMCPToolDefinition>?,
    val isConnected: Boolean,
    val processStatus: ProcessStatus?,
    val connectedAt: Instant?,
    val lastActivityAt: Instant?,
    val isResponsive: Boolean?,
) {
    val serverId: Long get() = serverConfig.id
}