package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.LocalMCPServerRuntimeStatusRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.LocalMCPServerApi
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default API-backed implementation of [LocalMCPServerRuntimeStatusRepository].
 *
 * @property api API client used to fetch runtime status snapshots from server-owned endpoints.
 */
class DefaultLocalMCPServerRuntimeStatusRepository(
    private val api: LocalMCPServerApi
) : LocalMCPServerRuntimeStatusRepository {
    /**
     * Mutable cache state for runtime statuses indexed by server identifier.
     */
    private val _runtimeStatuses =
        MutableStateFlow<DataState<RepositoryError, Map<Long, LocalMcpServerRuntimeStatusDto>>>(DataState.Idle)

    override val runtimeStatuses: StateFlow<DataState<RepositoryError, Map<Long, LocalMcpServerRuntimeStatusDto>>> =
        _runtimeStatuses.asStateFlow()

    override suspend fun loadRuntimeStatuses(): Either<RepositoryError, Unit> = either {
        _runtimeStatuses.update { DataState.Loading }
        val statuses = withError({ apiError ->
            apiError.toRepositoryError("Failed to load MCP server runtime statuses")
        }) {
            api.listRuntimeStatuses().bind()
        }

        _runtimeStatuses.update {
            DataState.Success(statuses.associateBy { status -> status.serverId })
        }
    }

    override suspend fun loadRuntimeStatus(
        serverId: Long
    ): Either<RepositoryError, LocalMcpServerRuntimeStatusDto> = either {
        val status = withError({ apiError ->
            apiError.toRepositoryError("Failed to load MCP server runtime status")
        }) {
            api.getRuntimeStatus(serverId).bind()
        }

        _runtimeStatuses.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(currentState.data + (status.serverId to status))
                else -> DataState.Success(mapOf(status.serverId to status))
            }
        }

        status
    }
}

