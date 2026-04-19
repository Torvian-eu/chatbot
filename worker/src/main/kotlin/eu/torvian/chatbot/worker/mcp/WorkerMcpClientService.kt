package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import io.modelcontextprotocol.kotlin.sdk.types.Tool

/**
 * Runtime MCP SDK client abstraction used by worker-local server control services.
 */
interface WorkerMcpClientService {
    /**
     * Starts process runtime and establishes MCP SDK connection.
     *
     * @param config Resolved local MCP server configuration.
     * @return Either start/connect error or Unit.
     */
    suspend fun startAndConnect(config: LocalMCPServerDto): Either<WorkerMcpClientStartError, Unit>

    /**
     * Disconnects MCP SDK client and stops backing process.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either stop error or Unit.
     */
    suspend fun stopServer(serverId: Long): Either<WorkerMcpClientStopError, Unit>

    /**
     * Discovers tools from a connected MCP SDK client.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either discovery error or discovered tool list.
     */
    suspend fun discoverTools(serverId: Long): Either<WorkerMcpClientDiscoverToolsError, List<Tool>>

    /**
     * Indicates whether a connected SDK client is currently tracked for one server.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return True when a client is currently registered.
     */
    fun isClientConnected(serverId: Long): Boolean

    /**
     * Disconnects all clients and stops managed processes.
     */
    suspend fun close()
}

