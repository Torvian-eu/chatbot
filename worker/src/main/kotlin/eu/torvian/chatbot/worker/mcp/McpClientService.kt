package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonObject

/**
 * Runtime MCP SDK client abstraction used by worker-local server control services.
 */
interface McpClientService {
    /**
     * Starts process runtime and establishes MCP SDK connection.
     *
     * @param config Resolved local MCP server configuration.
     * @return Either start/connect error or Unit.
     */
    suspend fun startAndConnect(config: LocalMCPServerDto): Either<McpClientStartError, Unit>

    /**
     * Disconnects MCP SDK client and stops backing process.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either stop error or Unit.
     */
    suspend fun stopServer(serverId: Long): Either<McpClientStopError, Unit>

    /**
     * Discovers tools from a connected MCP SDK client.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either discovery error or discovered tool list.
     */
    suspend fun discoverTools(serverId: Long): Either<McpClientDiscoverToolsError, List<Tool>>

    /**
     * Pings one connected MCP SDK client to verify transport responsiveness.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either ping error or Unit when the client responds.
     */
    suspend fun pingClient(serverId: Long): Either<McpClientPingError, Unit>

    /**
     * Calls one MCP tool on a connected SDK client.
     *
     * @param serverId Persisted local MCP server identifier.
     * @param toolName MCP tool name to invoke.
     * @param arguments JSON argument object passed to the tool.
     * @return Either call failure or MCP SDK call result.
     */
    suspend fun callTool(
        serverId: Long,
        toolName: String,
        arguments: JsonObject
    ): Either<McpClientCallToolError, CallToolResult>

    /**
     * Indicates whether a connected SDK client is currently tracked for one server.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return True when a client is currently registered.
     */
    fun isClientConnected(serverId: Long): Boolean

    /**
     * Returns connection-status metadata for one server if the worker currently tracks a connected client.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Connection-status snapshot or null when no connected client is tracked.
     */
    fun getConnectionStatus(serverId: Long): McpClientConnectionStatus?

    /**
     * Disconnects all clients and stops managed processes.
     *
     * This method is expected to be called during worker runtime shutdown to prevent orphaned
     * local MCP processes.
     */
    suspend fun close()
}

