package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlin.time.Instant

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
     * Returns connection-status metadata for one server if the worker currently tracks a connected client.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Connection-status snapshot or null when no connected client is tracked.
     */
    fun getConnectionStatus(serverId: Long): WorkerMcpClientConnectionStatus?

    /**
     * Disconnects all clients and stops managed processes.
     *
     * //TODO: This is currently unused; Integrate with worker lifecycle management and ensure proper cleanup on shutdown to avoid orphaned processes.
     */
    suspend fun close()
}

/**
 * Connection metadata snapshot for one worker-managed MCP client.
 *
 * @property connectedAt Timestamp when the SDK client connection was established.
 * @property lastActivityAt Timestamp of the latest observed client activity.
 */
data class WorkerMcpClientConnectionStatus(
    val connectedAt: Instant,
    val lastActivityAt: Instant
)

