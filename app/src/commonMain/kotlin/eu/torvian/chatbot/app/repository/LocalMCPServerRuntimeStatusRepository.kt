package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository abstraction for loading and caching Local MCP runtime status read models.
 *
 * The runtime ownership lives on worker/server, so this repository keeps the app decoupled from
 * worker internals while still exposing reactive runtime status updates for UI aggregation.
 */
interface LocalMCPServerRuntimeStatusRepository {
    /**
     * Reactive stream of runtime status snapshots indexed by server identifier.
     */
    val runtimeStatuses: StateFlow<DataState<RepositoryError, Map<Long, LocalMcpServerRuntimeStatusDto>>>

    /**
     * Loads runtime statuses for all user-owned Local MCP servers from the server API.
     *
     * @return Either repository error or Unit when refresh completes.
     */
    suspend fun loadRuntimeStatuses(): Either<RepositoryError, Unit>

    /**
     * Loads runtime status for one Local MCP server and updates the in-memory cache.
     *
     * @param serverId Persisted Local MCP server identifier.
     * @return Either repository error or loaded runtime status snapshot.
     */
    suspend fun loadRuntimeStatus(serverId: Long): Either<RepositoryError, LocalMcpServerRuntimeStatusDto>
}

