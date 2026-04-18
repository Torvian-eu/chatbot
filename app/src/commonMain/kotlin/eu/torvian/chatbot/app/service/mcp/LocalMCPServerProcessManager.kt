package eu.torvian.chatbot.app.service.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Platform-independent interface for managing the lifecycle of local MCP server processes.
 *
 * This interface provides low-level process management operations:
 * - Starting processes with STDIO communication
 * - Stopping processes gracefully (with forceful fallback)
 * - Querying process status
 * - Tracking active processes
 *
 * Platform-specific implementations are responsible for:
 * - Process creation and lifecycle management
 * - Environment setup and working directory configuration
 * - Signal handling and graceful shutdown
 * - Stream I/O coordination with the MCP protocol
 *
 * Design principles:
 * - Platform-independent process abstraction
 * - Pure process management (no MCP SDK knowledge)
 * - Stateless operations (config passed as parameter)
 * - Thread-safe process tracking for high concurrency
 * - Comprehensive error handling via Either
 * - Proper resource cleanup
 *
 * This component is called by MCPClientService (US2.3), which wraps the MCP SDK
 * for higher-level operations like tool discovery and execution.
 *
 * Platform-specific implementations:
 * - Desktop: LocalMCPServerProcessManagerDesktop (uses Java Process API)
 * - Other platforms: Can provide alternative implementations
 */
interface LocalMCPServerProcessManager {
    /**
     * Starts an MCP server process.
     *
     * @param config The MCP server configuration
     * @return Either.Right with ProcessStatus on success, or Either.Left with StartServerError on failure
     */
    suspend fun startServer(config: LocalMCPServerDto): Either<StartServerError, ProcessStatus>

    /**
     * Stops an MCP server process.
     *
     * This method attempts a graceful shutdown first, then forcefully kills it if it
     * doesn't stop within the timeout. It atomically removes the process from tracking.
     *
     * @param serverId The ID of the MCP server to stop
     * @return Either.Right with Unit on success, or Either.Left with StopServerError on failure
     */
    suspend fun stopServer(serverId: Long): Either<StopServerError, Unit>

    /**
     * Gets the status of an MCP server process.
     *
     * This function never returns an error. Instead, it returns STOPPED status
     * when a process doesn't exist or has crashed.
     *
     * @param serverId The ID of the MCP server
     * @return ProcessStatus with current state of the server
     */
    suspend fun getServerStatus(serverId: Long): ProcessStatus

    /**
     * Restarts an MCP server process.
     *
     * This is a convenience method that stops the server (if running) and then starts it again.
     *
     * @param config The MCP server configuration
     * @return Either.Right with ProcessStatus on success, or Either.Left with RestartServerError on failure
     */
    suspend fun restartServer(config: LocalMCPServerDto): Either<RestartServerError, ProcessStatus>

    /**
     * Stops all running MCP server processes.
     *
     * This method is intended to be called during application shutdown to ensure
     * all processes are properly cleaned up.
     *
     * @return Number of processes that were stopped successfully
     */
    suspend fun stopAllServers(): Int

    /**
     * Gets the input stream of a running MCP server process.
     *
     * This is used by MCPClientService to read from the process STDOUT.
     *
     * @param serverId The ID of the MCP server
     * @return The process input stream, or null if the process is not running
     */
    fun getProcessInputStream(serverId: Long): Source?

    /**
     * Gets the output stream of a running MCP server process.
     *
     * This is used by MCPClientService to write to the process STDIN.
     *
     * @param serverId The ID of the MCP server
     * @return The process output stream, or null if the process is not running
     */
    fun getProcessOutputStream(serverId: Long): Sink?

    /**
     * Gets the error stream of a running MCP server process.
     *
     * This is used for logging and debugging.
     *
     * @param serverId The ID of the MCP server
     * @return The process error stream, or null if the process is not running
     */
    fun getProcessErrorStream(serverId: Long): Source?

    /**
     * Closes the process manager and releases any resources.
     *
     * This method is idempotent and can be called multiple times.
     */
    suspend fun close()
}
