package eu.torvian.chatbot.app.service.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStateDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition

/**
 * Data class representing the aggregate status of a Local MCP Server.
 *
 * @property serverConfig The full LocalMCPServerDto configuration
 * @property tools List of tools discovered from the server (null if not discovered)
 * @property runtimeStatus Worker-backed runtime status snapshot for this server
 * @property isConnected Whether runtime status indicates an active worker client connection
 */
data class LocalMCPServerOverview(
    val serverConfig: LocalMCPServerDto,
    val tools: List<LocalMCPToolDefinition>?,
    val runtimeStatus: LocalMcpServerRuntimeStatusDto?
) {
    /**
     * The server identifier derived from [serverConfig].
     */
    val serverId: Long get() = serverConfig.id

    /**
     * Indicates whether worker runtime status currently reports this server as running and connected.
     */
    val isConnected: Boolean
        get() = runtimeStatus?.state == LocalMcpServerRuntimeStateDto.RUNNING && runtimeStatus.connectedAt != null
}