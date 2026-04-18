package eu.torvian.chatbot.app.service.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Service interface for MCP-specific operations.
 *
 * This service provides a clean abstraction layer for MCP SDK interactions,
 * managing MCP client connections and coordinating with LocalMCPServerProcessManager
 * for process lifecycle operations.
 *
 * Design principles:
 * - Pure MCP operations layer (no Repository/API dependencies)
 * - Stateless design (config passed as parameter)
 * - Manages only active client connections
 * - Wraps MCP Kotlin SDK for easier testing and abstraction
 *
 * Platform availability:
 * - Desktop: ✅ Full support
 * - Android: ✅ Full support
 * - WASM: ❌ Not available (requires process management)
 */
interface MCPClientService {

    /**
     * Reactive stream of all active MCP clients.
     *
     * This Flow provides real-time updates whenever the MCP client data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return Flow containing the current state of all MCP clients
     */
    val clients: Flow<Map<Long, MCPClient>>

    // ----- Lifecycle operations -----

    /**
     * Starts an MCP server process and establishes an MCP SDK client connection.
     *
     * This method performs the following steps:
     * 1. Calls `LocalMCPServerProcessManager` to start the server process using the
     *    provided `config`.
     * 2. Creates an MCP SDK `Client` instance and attaches a transport (stdio on
     *    desktop/android).
     * 3. Connects the SDK client to the server process and stores the client in
     *    internal state for later use.
     *
     * If a client is already connected for the given server id, a suitable
     * `StartAndConnectError` is returned.
     *
     * @param config Full `LocalMCPServerDto` configuration (contains id and name)
     * @return Either.Right(Unit) on success, or Either.Left(StartAndConnectError) on failure
     */
    suspend fun startAndConnect(config: LocalMCPServerDto): Either<StartAndConnectError, Unit>

    /**
     * Stops an MCP server process and disconnects the MCP SDK client.
     *
     * This method will:
     * 1. Disconnect the SDK client associated with `serverId` (if connected).
     * 2. Ask `LocalMCPServerProcessManager` to stop the underlying process.
     * 3. Remove any internal client references for the server.
     *
     * @param serverId Unique identifier of the MCP server to stop
     * @return Either.Right(Unit) on success, or Either.Left(MCPStopServerError) on failure
     */
    suspend fun stopServer(serverId: Long): Either<MCPStopServerError, Unit>

    /**
     * Disconnects all MCP SDK clients and stops all running servers managed by this service.
     *
     * Intended to be used during shutdown to ensure resources are cleaned up.
     *
     * @return Number of clients that were successfully disconnected/stopped
     */
    suspend fun disconnectAll(): Int

    // ----- Tool operations -----

    /**
     * Discovers tools available on the running MCP server.
     *
     * Uses the connected SDK client for the given server and calls into the
     * MCP SDK to list available tools.
     *
     * @param serverId Server identifier
     * @return Either.Right(list of Tool) on success or Either.Left(DiscoverToolsError)
     */
    suspend fun discoverTools(serverId: Long): Either<DiscoverToolsError, List<Tool>>

    /**
     * Executes a tool on a running MCP server and returns the raw SDK result.
     *
     * The caller is responsible for interpreting the SDK result and handling
     * any domain-specific conversion.
     *
     * @param serverId Server identifier
     * @param toolName MCP tool name to invoke
     * @param arguments Tool arguments as a JsonObject
     * @return Either.Right(CallToolResultBase?) on success or Either.Left(CallToolError)
     */
    suspend fun callTool(
        serverId: Long,
        toolName: String,
        arguments: JsonObject
    ): Either<CallToolError, CallToolResult?>

    // ----- Status & health -----

    /**
     * Returns the current process status for the given server id.
     *
     * Delegates to `LocalMCPServerProcessManager` for authoritative status.
     *
     * @param serverId Server identifier
     * @return Current ProcessStatus for the server
     */
    suspend fun getServerStatus(serverId: Long): ProcessStatus

    /**
     * Quick synchronous check whether an MCP SDK client exists in the internal
     * in-memory map for the given server id.
     *
     * Note: this does not perform any I/O or health probing of the transport.
     * Use `pingClient` for a real transport health check.
     *
     * @param serverId Server identifier
     * @return true if a client entry exists (registered), false otherwise
     */
    fun isClientRegistered(serverId: Long): Boolean

    /**
     * Performs a lightweight health-check (ping) against the MCP SDK client
     * transport for the provided server id.
     *
     * - Returns false immediately if no client exists for the server id.
     * - Otherwise performs an I/O ping via the SDK and returns true when the
     *   ping succeeds, false on any error.
     *
     * @param serverId Server identifier
     * @return true if ping succeeds, false otherwise
     */
    suspend fun pingClient(serverId: Long): Boolean

    // ----- Client enumeration & access -----

    /**
     * Returns a public `MCPClient` snapshot for the given server id, or null if
     * no client is connected for that server.
     *
     * This is a read-only, in-memory conversion and does not perform I/O.
     */
    fun getClient(serverId: Long): MCPClient?

    /**
     * Returns a snapshot list of active MCP clients along with their process status
     * and an optional recent connectivity check result.
     *
     * The returned objects are plain data objects and safe to inspect without
     * performing network I/O.
     *
     * @return List of MCPClient objects representing current client state
     */
    fun listClients(): List<MCPClient>

    /**
     * Disconnects all MCP SDK clients and stops all running servers.
     *
     * This method should be called during application shutdown to ensure
     * all resources are properly cleaned up.
     */
    suspend fun close()
}
