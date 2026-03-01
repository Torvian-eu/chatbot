package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing Local MCP Server configurations with local storage.
 *
 * This repository serves as the single source of truth for MCP server data in the application,
 * providing reactive data streams through StateFlow and handling all MCP server-related operations.
 * It abstracts the underlying local database (SQLDelight) and API layers, providing comprehensive
 * error handling through the RepositoryError hierarchy.
 *
 * The repository maintains an internal cache of MCP server data and automatically updates
 * all observers when changes occur, ensuring data consistency across the application.
 *
 * **Storage Architecture:**
 * - **Client-side**: Full MCP server configuration stored locally using SQLDelight
 * - **Server-side**: Only `id` and `userId` stored for linkage purposes
 * - **Platform-specific**: Each platform (Desktop, Android, WASM) has independent storage
 * - **Encryption**: Environment variables encrypted client-side using CryptoProvider
 *
 * **Separation of Concerns:**
 * - LocalMCPServerRepository manages MCP server state (this interface)
 * - ToolRepository manages tool state (including MCP tools)
 * - LocalMCPServerManager orchestrates operations between the two (US2.2)
 */
interface LocalMCPServerRepository {

    /**
     * Reactive stream of all MCP server configurations for the current user.
     *
     * This StateFlow provides real-time updates whenever the MCP server data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of all MCP servers wrapped in DataState
     */
    val servers: StateFlow<DataState<RepositoryError, List<LocalMCPServer>>>

    /**
     * Loads all MCP server configurations for the current user from the local database.
     *
     * This operation fetches the latest MCP server data and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @param userId The ID of the current user whose MCP servers should be loaded.
     */
    suspend fun loadServers(userId: Long)

    /**
     * Creates a new MCP server configuration.
     *
     * This operation:
     * 1. Requests a unique ID from the server API (POST /api/v1/mcp-servers/generate-id)
     * 2. Stores the full configuration locally using SQLDelight (with server-generated ID)
     * 3. Updates the internal StateFlow
     *
     * Upon successful creation, the new server is automatically added to the internal
     * StateFlow, triggering updates to all observers.
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
     * @return Either.Right with the created LocalMCPServer on success, or Either.Left with RepositoryError on failure
     */
    suspend fun createServer(
        name: String,
        description: String?,
        command: String,
        arguments: List<String>,
        environmentVariables: Map<String, String>,
        workingDirectory: String?,
        isEnabled: Boolean,
        autoStartOnEnable: Boolean,
        autoStartOnLaunch: Boolean,
        autoStopAfterInactivitySeconds: Int?,
        toolNamePrefix: String? = null,
    ): Either<RepositoryError, LocalMCPServer>

    /**
     * Updates an existing MCP server configuration.
     *
     * This operation updates the local SQLDelight database only. No server API call is made
     * (server only stores id and userId, which don't change).
     *
     * Upon successful update, the modified server replaces the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param server The updated MCP server object with the same ID as the existing server
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateServer(server: LocalMCPServer): Either<RepositoryError, Unit>

    /**
     * Deletes an MCP server configuration.
     *
     * This operation:
     * 1. Deletes from local SQLDelight database
     * 2. Calls server API to delete the ID record (DELETE /api/v1/mcp-servers/{id})
     *
     * Upon successful deletion, the server is automatically removed from the internal
     * StateFlow, triggering updates to all observers.
     *
     * **Note**: Associated tools are handled by the server-side CASCADE delete on
     * LocalMCPToolDefinitionTable. ToolRepository should invalidate its cache after
     * server deletion to reflect removed tools.
     *
     * @param serverId The unique identifier of the MCP server to delete
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteServer(serverId: Long): Either<RepositoryError, Unit>
}
