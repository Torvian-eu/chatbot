package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Process-level runtime abstraction for worker-managed local MCP servers.
 */
interface McpProcessManager {
    /**
     * Starts a local MCP server process for the provided configuration.
     *
     * @param config Resolved local MCP server configuration.
     * @return Either start failure or current running status.
     */
    suspend fun startServer(config: LocalMCPServerDto): Either<WorkerLocalMcpStartProcessError, McpProcessStatus>

    /**
     * Stops one local MCP server process.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either stop failure or Unit when the process is terminated.
     */
    suspend fun stopServer(serverId: Long): Either<WorkerLocalMcpStopProcessError, Unit>

    /**
     * Returns process status for one local MCP server identifier.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Current process status snapshot.
     */
    suspend fun getServerStatus(serverId: Long): McpProcessStatus

    /**
     * Returns process stdout stream for MCP stdio transport.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Buffered stdout source or null when process is not running.
     */
    fun getProcessInputStream(serverId: Long): Source?

    /**
     * Returns process stdin stream for MCP stdio transport.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Buffered stdin sink or null when process is not running.
     */
    fun getProcessOutputStream(serverId: Long): Sink?

    /**
     * Returns process stderr stream for diagnostics.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Buffered stderr source or null when process is not running.
     */
    fun getProcessErrorStream(serverId: Long): Source?

    /**
     * Stops all tracked processes and releases process-manager resources.
     */
    suspend fun close()
}

