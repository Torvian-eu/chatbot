package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import arrow.core.right
import eu.torvian.chatbot.app.database.dao.LocalMCPServerLocalDao
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.LocalMCPServerRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.LocalMCPServerApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * @property toolRepository The tool repository for accessing MCP server tools
 * @property authRepository The auth repository for accessing current user information
 */
class DefaultLocalMCPServerRepository(
    private val localDao: LocalMCPServerLocalDao,
    private val api: LocalMCPServerApi,
    private val toolRepository: ToolRepository,
    private val authRepository: AuthRepository
) : LocalMCPServerRepository {

    companion object {
        private val logger = kmpLogger<DefaultLocalMCPServerRepository>()
    }

    private val _servers = MutableStateFlow<DataState<RepositoryError, List<LocalMCPServer>>>(DataState.Idle)
    override val servers: StateFlow<DataState<RepositoryError, List<LocalMCPServer>>> = _servers.asStateFlow()

    private val loadMutex = Mutex()

    /**
     * Gets the current user ID from the auth repository.
     * @throws IllegalStateException if user is not logged in
     */
    private fun getCurrentUserId(): Long {
        return (authRepository.authState.value as? AuthState.Authenticated)?.userId
            ?: throw IllegalStateException("User must be logged in to access MCP servers")
    }

    override suspend fun loadServers(): Either<RepositoryError, Unit> = either {
        // Prevent duplicate loading operations
        if (_servers.value.isLoading) return Unit.right()

        loadMutex.withLock {
            // Double-check after acquiring lock
            if (_servers.value.isLoading) return Unit.right()

            _servers.update { DataState.Loading }

            try {
                // Load from local database
                val serverList = localDao.getAll(getCurrentUserId())
                _servers.update { DataState.Success(serverList) }
            } catch (e: Exception) {
                logger.error("Failed to load MCP servers from local database", e)
                val error = RepositoryError.OtherError(
                    description = "Failed to load MCP servers from local database",
                    cause = e
                )
                _servers.update { DataState.Error(error) }
                raise(error)
            }
        }
    }

    override suspend fun getServerById(serverId: Long): Either<RepositoryError, LocalMCPServer> = either {
        withError({ _daoError ->
            RepositoryError.OtherError(
                description = "MCP server not found: $serverId",
                cause = null
            )
        }) {
            localDao.getById(serverId).bind()
        }
    }

    override suspend fun createServer(server: LocalMCPServer): Either<RepositoryError, LocalMCPServer> = either {
        // Step 1: Request ID from server API
        val serverIdResponse = withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to generate MCP server ID")
        }) {
            api.generateServerId().bind()
        }

        // Step 2: Create local server configuration with generated ID
        val serverWithId = server.copy(
            id = serverIdResponse.id,
            userId = serverIdResponse.userId
        )

        // Step 3: Store in local database
        val createdServer = try {
            localDao.insert(serverWithId)
        } catch (e: Exception) {
            logger.error("Failed to insert MCP server into local database", e)
            raise(
                RepositoryError.OtherError(
                    description = "Failed to insert MCP server into local database",
                    cause = e
                )
            )
        }

        // Step 4: Update cache
        _servers.update { currentState ->
            when (currentState) {
                is DataState.Success -> {
                    val updatedServers = currentState.data + createdServer
                    DataState.Success(updatedServers)
                }
                else -> currentState // Keep other states unchanged
            }
        }

        createdServer
    }

    override suspend fun updateServer(server: LocalMCPServer): Either<RepositoryError, Unit> = either {
        // Update local database
        withError({ _daoError ->
            RepositoryError.OtherError(
                description = "Failed to update MCP server in local database",
                cause = null
            )
        }) {
            localDao.update(server).bind()
        }

        // Update cache
        _servers.update { currentState ->
            when (currentState) {
                is DataState.Success -> {
                    val updatedServers = currentState.data.map { if (it.id == server.id) server else it }
                    DataState.Success(updatedServers)
                }
                else -> currentState // Keep other states unchanged
            }
        }
    }

    override suspend fun deleteServer(serverId: Long): Either<RepositoryError, Unit> = either {
        // Step 1: Delete from local database
        withError({ _daoError ->
            RepositoryError.OtherError(
                description = "Failed to delete MCP server from local database",
                cause = null
            )
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
        _servers.update { currentState ->
            when (currentState) {
                is DataState.Success -> {
                    val updatedServers = currentState.data.filter { it.id != serverId }
                    DataState.Success(updatedServers)
                }
                else -> currentState // Keep other states unchanged
            }
        }

        // Note: ToolRepository will invalidate its cache when tools are refreshed
        // LocalMCPServerManager should coordinate this if needed
    }
}

