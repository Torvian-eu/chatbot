package eu.torvian.chatbot.server.worker.mcp.configsync

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPServerDto
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchService

/**
 * Default low-level worker-sync adapter for Local MCP cache mutation commands.
 *
 * @property localMCPRuntimeCommandDispatchService Worker command adapter used to transport cache-sync requests.
 */
class DefaultLocalMCPServerWorkerSyncService(
    private val localMCPRuntimeCommandDispatchService: LocalMCPRuntimeCommandDispatchService
) : LocalMCPServerWorkerSyncService {
    override suspend fun syncCreated(
        signedServer: SignedLocalMCPServerDto
    ): Either<LocalMCPRuntimeCommandDispatchError, Unit> =
        localMCPRuntimeCommandDispatchService.createServer(
            workerId = signedServer.server.workerId,
            signedServer = signedServer
        ).map { }

    override suspend fun syncUpdated(
        signedServer: SignedLocalMCPServerDto,
        previousWorkerId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, Unit> = either {
        if (previousWorkerId == signedServer.server.workerId) {
            localMCPRuntimeCommandDispatchService.updateServer(
                workerId = signedServer.server.workerId,
                signedServer = signedServer
            ).bind()
            return@either
        }

        // Remove the stale cache entry first so the previous worker cannot keep serving the reassigned config.
        localMCPRuntimeCommandDispatchService.deleteServer(
            workerId = previousWorkerId,
            serverId = signedServer.server.id
        ).bind()

        localMCPRuntimeCommandDispatchService.createServer(
            workerId = signedServer.server.workerId,
            signedServer = signedServer
        ).bind()
    }

    override suspend fun syncDeleted(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, Unit> =
        localMCPRuntimeCommandDispatchService.deleteServer(workerId = workerId, serverId = serverId).map { }
}