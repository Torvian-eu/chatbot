package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.app.database.dao.LocalMCPServerLocalDao
import eu.torvian.chatbot.app.database.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.app.database.dao.error.UpdateLocalMCPServerError
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.repository.LocalMCPServerRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.RepositoryError.OtherError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.LocalMCPServerApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.mcp.CreateServerRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock

/**
 * Default implementation of [LocalMCPServerRepository] that manages MCP server configurations
 * with client-side local storage.
 *
 * This repository maintains an internal cache of MCP server data using [MutableStateFlow] and
 * provides reactive updates to all observers. It delegates local storage operations to the
 * injected [LocalMCPServerLocalDao] and server API operations to [LocalMCPServerApi].
 *
 * The repository ensures data consistency by automatically updating the internal StateFlow
 * whenever successful CRUD operations occur, eliminating the need for manual cache invalidation.
 *
 * **Storage Strategy:**
 * - Client-side: Full configuration stored in SQLDelight (platform-specific)
 * - Server-side: Only ID and userId stored for linkage and ownership
 *
 * **Separation of Concerns:**
 * - This repository manages only MCP server state
 * - Tool management is delegated to ToolRepository
 * - Operation orchestration is handled by LocalMCPServerManager
 *
 * @property localDao The local DAO for client-side MCP server storage
 * @property api The API client for server-side MCP server ID management
 */
class DefaultLocalMCPServerRepository(
    private val localDao: LocalMCPServerLocalDao,
    private val api: LocalMCPServerApi
) : LocalMCPServerRepository {

    companion object {
        private val logger = kmpLogger<DefaultLocalMCPServerRepository>()
    }

    private val _servers = MutableStateFlow<DataState<RepositoryError, List<LocalMCPServer>>>(DataState.Idle)
    override val servers: StateFlow<DataState<RepositoryError, List<LocalMCPServer>>> = _servers.asStateFlow()

    override suspend fun loadServers(userId: Long) {
        // Prevent duplicate loading operations
        if (_servers.value.isLoading) return
        _servers.update { DataState.Loading }

        // Load from local database
        val serverList = localDao.getAll(userId)
        _servers.update { DataState.Success(serverList) }

    }

    override suspend fun createServer(
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
        toolsEnabledByDefault: Boolean
    ): Either<RepositoryError, LocalMCPServer> = either {
        // Step 1: Request server creation from server API
        val serverResponse = withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to create MCP server")
        }) {
            api.createServer(CreateServerRequest(isEnabled = isEnabled)).bind()
        }

        // Step 2: Create local server configuration with generated ID
        val server = LocalMCPServer(
            id = serverResponse.id,
            userId = serverResponse.userId,
            name = name,
            description = description,
            command = command,
            arguments = arguments,
            environmentVariables = environmentVariables,
            workingDirectory = workingDirectory,
            isEnabled = isEnabled,
            autoStartOnEnable = autoStartOnEnable,
            autoStartOnLaunch = autoStartOnLaunch,
            autoStopAfterInactivitySeconds = autoStopAfterInactivitySeconds,
            toolsEnabledByDefault = toolsEnabledByDefault,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )

        // Step 3: Store in local database
        val createdServer = localDao.insert(server)

        // Step 4: Update cache
        updateCache { it + createdServer }

        createdServer
    }

    override suspend fun updateServer(server: LocalMCPServer): Either<RepositoryError, Unit> = either {
        // Step 1: Update local database
        withError({ daoError ->
            logger.error("Failed to update MCP server ${server.id}: $daoError")
            when (daoError) {
                is UpdateLocalMCPServerError.NotFound -> OtherError("MCP server not found: ${server.id}")
                is UpdateLocalMCPServerError.DuplicateName -> OtherError("Duplicate server name: ${server.name}")
                is UpdateLocalMCPServerError.EncryptionFailed -> OtherError("Failed to encrypt environment variables")
            }
        }) {
            localDao.update(server).bind()
        }

        // Step 2: Sync isEnabled state with server API only if it changed
        val cachedServer = getCachedServerById(server.id)
        if (cachedServer?.isEnabled != server.isEnabled) {
            withError({ apiResourceError ->
                apiResourceError.toRepositoryError("Failed to sync server enabled state")
            }) {
                api.setServerEnabled(server.id, server.isEnabled).bind()
            }
        }

        // Step 3: Update cache
        updateCache { list -> list.map { if (it.id == server.id) server else it } }
    }

    override suspend fun deleteServer(serverId: Long): Either<RepositoryError, Unit> = either {
        // Step 1: Delete from local database
        withError({ daoError ->
            logger.error("Failed to delete MCP server $serverId: $daoError")
            when (daoError) {
                is DeleteLocalMCPServerError.NotFound -> OtherError("MCP server not found: $serverId")
                is DeleteLocalMCPServerError.SecretCleanupFailed -> OtherError("Failed to cleanup encrypted secret")
            }
        }) {
            localDao.delete(serverId).bind()
        }

        // Step 2: Delete from server API (triggers CASCADE delete on tools)
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to delete MCP server ID from server")
        }) {
            api.deleteServerId(serverId).bind()
        }

        // Step 3: Update cache
        updateCache { list -> list.filter { it.id != serverId } }
    }

    /**
     * Update the internal `_servers` cache using [transform] when the current cache is in
     * [DataState.Success]. If the cache is in any other state the update is skipped and a
     * warning is logged.
     *
     * This helper centralizes mutation logic so all callers can provide a pure list
     * transformation and not worry about the current state of the cache.
     *
     * @param transform Pure function that receives the current list of [LocalMCPServer] and
     * returns the new list to store in the cache.
     */
    private fun updateCache(transform: (List<LocalMCPServer>) -> List<LocalMCPServer>) {
        _servers.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                else -> {
                    logger.warn("Skipping cache update because current state is not Success: $currentState")
                    currentState
                }
            }
        }
    }

    /**
     * Helper to retrieve a single cached server by ID.
     *
     * @param serverId The ID of the server to retrieve
     * @return The cached server or null if not found
     */
    private fun getCachedServerById(serverId: Long): LocalMCPServer? =
        _servers.value.dataOrNull?.find { it.id == serverId }

}
