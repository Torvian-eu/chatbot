package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.repository.LocalMCPServerRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dummy (no-op) implementation of [LocalMCPServerRepository] for platforms that do not
 * support local MCP server management (e.g. WASM/JS).
 *
 * All write operations succeed immediately without doing anything.
 * The [servers] flow always emits an empty success state.
 */
class LocalMCPServerRepositoryDummy : LocalMCPServerRepository {

    private val _servers = MutableStateFlow<DataState<RepositoryError, List<LocalMCPServer>>>(
        DataState.Success(emptyList())
    )

    override val servers: StateFlow<DataState<RepositoryError, List<LocalMCPServer>>>
        get() = _servers.asStateFlow()

    override suspend fun loadServers(userId: Long) {
        // No-op: local MCP servers are not supported on this platform
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
        toolNamePrefix: String?,
    ): Either<RepositoryError, LocalMCPServer> {
        // No-op: local MCP servers are not supported on this platform
        return RepositoryError.OtherError("Local MCP servers are not supported on this platform").let {
            Either.Left(it)
        }
    }

    override suspend fun updateServer(server: LocalMCPServer): Either<RepositoryError, Unit> {
        // No-op: local MCP servers are not supported on this platform
        return Unit.right()
    }

    override suspend fun deleteServer(serverId: Long): Either<RepositoryError, Unit> {
        // No-op: local MCP servers are not supported on this platform
        return Unit.right()
    }
}

