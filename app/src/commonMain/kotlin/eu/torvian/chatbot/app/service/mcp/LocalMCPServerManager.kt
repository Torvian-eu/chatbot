package eu.torvian.chatbot.app.service.mcp

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * High-level orchestration service for Local MCP Server workflows.
 *
 * This manager coordinates between:
 * - LocalMCPServerRepository (MCP server configurations)
 * - LocalMCPToolRepository (MCP tool persistence)
 * - MCPClientService (MCP operations)
 *
 * Design principles:
 * - High-level orchestration layer between UI and MCP operations
 * - Coordinates data flow across repositories and services
 * - Handles data transformation (MCP SDK Tool → LocalMCPToolDefinition)
 * - Does not manage state (that's Repository's job)
 * - Does not manage processes (that's MCPClientService's job)
 * - Does not call API directly (that's Repository's job)
 * - Pure business logic and workflow coordination
 *
 * Platform availability:
 * - Desktop: ✅ Full support
 * - Android: ✅ Full support
 * - WASM: ❌ Not available (requires process management)
 */
interface LocalMCPServerManager {

    /**
     * StateFlow providing reactive updates of the aggregate status of all MCP servers.
     *
     * This is a derived state computed from:
     * - MCPClientService.clients (active MCP SDK client connections)
     * - LocalMCPServerRepository.servers (MCP server configurations)
     * - LocalMCPToolRepository.tools (MCP tool definitions organized by server ID)
     *
     * The collection provides complete status information for each server,
     * including configuration, tools, process status, connection status, and responsiveness.
     *
     * Automatically updates whenever any of the source states change.
     *
     * The DataState reflects the loading state of the underlying server data:
     * - Idle: No data loaded yet
     * - Loading: Server data is being loaded
     * - Success: Server overviews are available (list may be empty if no servers configured)
     * - Error: Failed to load server data from repository
     *
     * @return StateFlow<DataState<RepositoryError, List<LocalMCPServerOverview>>> list of server overviews
     */
    val serverOverviews: StateFlow<DataState<RepositoryError, List<LocalMCPServerOverview>>>

    /**
     * Loads all MCP servers and their tools for a specific user.
     *
     * This operation:
     * 1. Calls LocalMCPServerRepository to load servers for the user
     * 2. Calls LocalMCPToolRepository to load all MCP tools
     *
     * @param userId The ID of the user whose servers to load
     * @return Either.Right(Unit) on success, or Either.Left(RepositoryError) on failure
     */
    suspend fun loadServers(userId: Long): Either<RepositoryError, Unit>

    /**
     * Tests connection to a new MCP server and returns the count of discovered tools.
     *
     * This operation:
     * 1. Creates a temporary server config
     * 2. Calls MCPClientService to start and connect
     * 3. Discovers tools via MCPClientService
     * 4. Stops the server (cleanup)
     * 5. Returns success/failure with tool count
     *
     * Server config is NOT persisted during testing.
     *
     * @param name The name of the MCP server
     * @param command The command to start the MCP server
     * @param arguments The arguments to start the MCP server
     * @param environmentVariables The environment variables to start the MCP server
     * @param workingDirectory The working directory to start the MCP server
     * @return Either.Right with tool count on success, or Either.Left with TestConnectionError on failure
     */
    suspend fun testConnectionForNewServer(
        name: String,
        command: String,
        arguments: List<String>,
        environmentVariables: Map<String, String> = emptyMap(),
        workingDirectory: String? = null
    ): Either<TestConnectionError, Int>


    /**
     * Creates a new MCP server and persists it to the database.
     *
     * This operation:
     * 1. Calls LocalMCPServerRepository to create server config
     * 2. Calls MCPClientService to start and connect
     * 3. Discovers tools via MCPClientService
     * 4. Converts MCP SDK Tool objects to LocalMCPToolDefinition format
     * 5. Calls LocalMCPToolRepository to persist tools (repository handles API call)
     * 6. Stops the server (cleanup)
     * 7. Returns created server
     *
     * @param name The name of the MCP server
     * @param description Optional description of the MCP server
     * @param command The command to start the MCP server
     * @param arguments The arguments to start the MCP server
     * @param environmentVariables The environment variables to start the MCP server
     * @param workingDirectory The working directory to start the MCP server
     * @param isEnabled Whether the server is globally enabled (if false, tools cannot be enabled for any session)
     * @param autoStartOnEnable Whether the server should be started when a tool is enabled for a session
     * @param autoStartOnLaunch Whether the server should be started when the application launches
     * @param autoStopAfterInactivitySeconds The number of seconds after which the server should be stopped if no tool calls are made
     * @param toolsEnabledByDefault Whether tools from this server are enabled by default for NEW chat sessions
     * @return Either.Right with created server on success, or Either.Left with CreateServerError on failure
     */
    suspend fun createServer(
        name: String,
        description: String? = null,
        command: String,
        arguments: List<String>,
        environmentVariables: Map<String, String> = emptyMap(),
        workingDirectory: String? = null,
        isEnabled: Boolean = true,
        autoStartOnEnable: Boolean = false,
        autoStartOnLaunch: Boolean = false,
        autoStopAfterInactivitySeconds: Int? = null,
        toolsEnabledByDefault: Boolean = false
    ): Either<CreateServerError, LocalMCPServer>

    /**
     * Tests connection to an existing MCP server and returns the count of discovered tools.
     *
     * This operation:
     * 1. Gets config from LocalMCPServerRepository
     * 2. Calls MCPClientService to start and connect (if not already connected)
     * 3. Discovers tools via MCPClientService
     * 4. Stops the server (if started by this operation)
     * 5. Returns success/failure with tool count
     *
     * Tools are NOT persisted during testing.
     *
     * @param serverId The ID of the MCP server to test
     * @return Either.Right with tool count on success, or Either.Left with TestConnectionError on failure
     */
    suspend fun testConnection(serverId: Long): Either<TestConnectionError, Int>

    /**
     * Refreshes tools from an MCP server by comparing with existing tools.
     *
     * This operation:
     * 1. Gets config from LocalMCPServerRepository
     * 2. Calls MCPClientService to start and connect (if not already connected)
     * 3. Discovers tools via MCPClientService
     * 4. Calls LocalMCPToolRepository to refresh tools (repository handles comparison and API call)
     * 5. Stops the server (if started by this operation)
     * 6. Returns refresh summary
     *
     * @param serverId The ID of the MCP server
     * @return Either.Right with refresh summary on success, or Either.Left with RefreshToolsError on failure
     */
    suspend fun refreshTools(serverId: Long): Either<RefreshToolsError, RefreshMCPToolsResponse>

    /**
     * Starts an MCP server process and connects the MCP SDK client.
     *
     * This operation:
     * 1. Gets config from LocalMCPServerRepository
     * 2. Calls MCPClientService to start and connect
     *
     * @param serverId The ID of the MCP server to start
     * @return Either.Right with Unit on success, or Either.Left with ManageStartServerError on failure
     */
    suspend fun startServer(serverId: Long): Either<ManageStartServerError, Unit>

    /**
     * Stops an MCP server process and disconnects the MCP SDK client.
     *
     * This operation:
     * 1. Calls MCPClientService to stop
     *
     * @param serverId The ID of the MCP server to stop
     * @return Either.Right with Unit on success, or Either.Left with ManageStopServerError on failure
     */
    suspend fun stopServer(serverId: Long): Either<ManageStopServerError, Unit>

    /**
     * Updates an existing MCP server configuration.
     *
     * This operation:
     * 1. Updates the server configuration (via LocalMCPServerRepository)
     * 2. If command, arguments, or environment variables changed:
     *    a. Stops the server if it's running (via MCPClientService)
     *    b. Starts and connects with new config (via MCPClientService)
     *    c. Discovers tools (via MCPClientService)
     *    d. Refreshes tools in repository (via LocalMCPToolRepository)
     *    e. Stops the server if it wasn't running before (cleanup)
     *
     * @param server The updated MCP server configuration
     * @return Either.Right with Unit on success, or Either.Left with UpdateServerError on failure
     */
    suspend fun updateServer(server: LocalMCPServer): Either<UpdateServerError, Unit>

    /**
     * Deletes an MCP server and all its associated tools.
     *
     * This operation:
     * 1. Stops the server if it's running (via MCPClientService)
     * 2. Deletes all tools for the server (via LocalMCPToolRepository)
     * 3. Deletes the server configuration (via LocalMCPServerRepository)
     *
     * @param serverId The ID of the MCP server to delete
     * @return Either.Right with Unit on success, or Either.Left with DeleteServerError on failure
     */
    suspend fun deleteServer(serverId: Long): Either<DeleteServerError, Unit>

    /**
     * Executes a tool on an MCP server.
     *
     * This method:
     * 1. Ensures the server is started and connected
     * 2. Calls `mcpSdkClient.callTool(serverId, toolName, arguments)`
     * 3. Returns the tool result or error
     *
     * @param serverId The unique identifier of the MCP server (needed to look up client)
     * @param toolName The name of the tool to execute (MCP tool name)
     * @param arguments The tool arguments as a JSON object
     * @return Either.Right with tool result, or Either.Left with ManageCallToolError
     */
    suspend fun callTool(
        serverId: Long,
        toolName: String,
        arguments: JsonObject
    ): Either<ManageCallToolError, CallToolResultBase?>


    /**
     * Cleans up resources when the manager is closed.
     */
    suspend fun close()
}
