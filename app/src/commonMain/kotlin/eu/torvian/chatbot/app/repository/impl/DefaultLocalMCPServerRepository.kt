package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.app.domain.models.toUpdateRequest
import eu.torvian.chatbot.app.repository.LocalMCPServerRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.LocalMCPServerApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerConnectionResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
/**
 * Default implementation of [LocalMCPServerRepository] backed by server-owned Local MCP CRUD APIs.
 *
 * This repository maintains an internal cache of MCP server data using [MutableStateFlow] and
 * provides reactive updates to all observers. Persistence source-of-truth is the backend API.
 *
 * The repository ensures data consistency by automatically updating the internal StateFlow
 * whenever successful CRUD operations occur, eliminating the need for manual cache invalidation.
 *
 * **Storage Strategy:**
 * - Server-side: full Local MCP configuration (authoritative)
 * - App-side: in-memory cache for reactive UI updates
 *
 * **Separation of Concerns:**
 * - This repository manages only MCP server state
 * - Tool management is delegated to ToolRepository
 * - Operation orchestration is handled by LocalMCPServerManager
 *
 * @property api API client for server-owned Local MCP server CRUD
 */
class DefaultLocalMCPServerRepository(
    private val api: LocalMCPServerApi
) : LocalMCPServerRepository {

    companion object {
        private val logger = kmpLogger<DefaultLocalMCPServerRepository>()
    }

    private val _servers = MutableStateFlow<DataState<RepositoryError, List<LocalMCPServerDto>>>(DataState.Idle)
    override val servers: StateFlow<DataState<RepositoryError, List<LocalMCPServerDto>>> = _servers.asStateFlow()

    override suspend fun loadServers(userId: Long) {
        // Prevent duplicate loading operations
        if (_servers.value.isLoading) return
        _servers.update { DataState.Loading }

        val result = api.getServers()
        _servers.update {
            result.fold(
                ifLeft = { apiError -> DataState.Error(apiError.toRepositoryError("Failed to load MCP servers")) },
                ifRight = { servers -> DataState.Success(servers) }
            )
        }

    }

    override suspend fun createServer(
        workerId: Long,
        name: String,
        description: String?,
        command: String,
        arguments: List<String>,
        environmentVariables: List<LocalMCPEnvironmentVariableDto>,
        secretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto>,
        workingDirectory: String?,
        isEnabled: Boolean,
        autoStartOnEnable: Boolean,
        autoStartOnLaunch: Boolean,
        autoStopAfterInactivitySeconds: Int?,
        toolNamePrefix: String?
    ): Either<RepositoryError, LocalMCPServerDto> = either {
        val request = CreateLocalMCPServerRequest(
            workerId = workerId,
            name = name,
            description = description,
            command = command,
            arguments = arguments,
            environmentVariables = environmentVariables,
            secretEnvironmentVariables = secretEnvironmentVariables,
            workingDirectory = workingDirectory,
            isEnabled = isEnabled,
            autoStartOnEnable = autoStartOnEnable,
            autoStartOnLaunch = autoStartOnLaunch,
            autoStopAfterInactivitySeconds = autoStopAfterInactivitySeconds,
            toolNamePrefix = toolNamePrefix
        )

        val createdServer = withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to create MCP server")
        }) {
            api.createServer(request).bind()
        }

        updateCache { it + createdServer }

        createdServer
    }

    override suspend fun updateServer(server: LocalMCPServerDto): Either<RepositoryError, Unit> = either {
        val updatedServer = withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to update MCP server")
        }) {
            api.updateServer(server.id, server.toUpdateRequest()).bind()
        }

        updateCache { list -> list.map { if (it.id == updatedServer.id) updatedServer else it } }
    }

    override suspend fun deleteServer(serverId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to delete MCP server")
        }) {
            api.deleteServer(serverId).bind()
        }

        updateCache { list -> list.filter { it.id != serverId } }
    }

    override suspend fun startServer(serverId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to start MCP server")
        }) {
            api.startServer(serverId).bind()
        }
    }

    override suspend fun stopServer(serverId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to stop MCP server")
        }) {
            api.stopServer(serverId).bind()
        }
    }

    override suspend fun testConnection(serverId: Long): Either<RepositoryError, TestLocalMCPServerConnectionResponse> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to test MCP server connection")
        }) {
            api.testConnection(serverId).bind()
        }
    }

    override suspend fun refreshTools(serverId: Long): Either<RepositoryError, RefreshMCPToolsResponse> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to refresh MCP server tools")
        }) {
            api.refreshTools(serverId).bind()
        }
    }

    /**
     * Update the internal `_servers` cache using [transform] when the current cache is in
     * [DataState.Success]. If the cache is in any other state the update is skipped and a
     * warning is logged.
     *
     * This helper centralizes mutation logic so all callers can provide a pure list
     * transformation and not worry about the current state of the cache.
     *
     * @param transform Pure function that receives the current list of [LocalMCPServerDto] and
     * returns the new list to store in the cache.
     */
    private fun updateCache(transform: (List<LocalMCPServerDto>) -> List<LocalMCPServerDto>) {
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


}
