package eu.torvian.chatbot.server.worker.mcp.configsync

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.service.core.LocalMCPServerSignerSnapshot
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerServiceError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default Local MCP write orchestrator that persists configuration and synchronizes worker cache state.
 *
 * @property localMCPServerService Persistence service for Local MCP server state.
 * @property localMCPServerWorkerSyncService Low-level worker sync adapter returning typed command failures.
 */
class DefaultLocalMCPServerConfigSyncService(
    private val localMCPServerService: LocalMCPServerService,
    private val localMCPServerWorkerSyncService: LocalMCPServerWorkerSyncService
) : LocalMCPServerConfigSyncService {
    override suspend fun createSignedServer(
        userId: Long,
        request: CreateLocalMCPServerRequest,
        signedRequest: SignedRequest
    ): Either<LocalMCPServerConfigSyncError, LocalMCPServerDto> = either {
        val createdServer = withError(::toServerOperationFailed) {
            localMCPServerService.createSignedServer(userId, request, signedRequest).bind()
        }
        val createdSignedServer = SignedLocalMCPServerDto(server = createdServer, signedRequest = signedRequest)

        when (val syncResult = localMCPServerWorkerSyncService.syncCreated(createdSignedServer)) {
            is Either.Right -> createdServer
            is Either.Left -> {
                val syncError = syncResult.value
                withError({ compensationError ->
                    LocalMCPServerConfigSyncError.CompensationFailed(
                        operation = "delete-created-server",
                        syncError = syncError,
                        compensationError = compensationError
                    )
                }) {
                    localMCPServerService.deleteServer(userId, createdServer.id).bind()
                }
                raise(LocalMCPServerConfigSyncError.WorkerSyncFailed(syncError))
            }
        }
    }

    override suspend fun updateSignedServer(
        userId: Long,
        serverId: Long,
        request: UpdateLocalMCPServerRequest,
        signedRequest: SignedRequest
    ): Either<LocalMCPServerConfigSyncError, LocalMCPServerDto> = either {
        val previousSignerSnapshot = withError(::toServerOperationFailed) {
            localMCPServerService.getServerSignerSnapshot(
                userId = userId,
                serverId = serverId,
                signerId = signedRequest.signerId
            ).bind()
        }
        val updatedServer = withError(::toServerOperationFailed) {
            localMCPServerService.updateSignedServer(userId, serverId, request, signedRequest).bind()
        }
        val updatedSignedServer = SignedLocalMCPServerDto(server = updatedServer, signedRequest = signedRequest)

        when (
            val syncResult = localMCPServerWorkerSyncService.syncUpdated(
                signedServer = updatedSignedServer,
                previousWorkerId = previousSignerSnapshot.server.workerId
            )
        ) {
            is Either.Right -> updatedServer
            is Either.Left -> {
                val syncError = syncResult.value
                val restoredSignerSnapshot = localMCPServerService.restoreServerSnapshot(
                    userId = userId,
                    serverId = serverId,
                    snapshot = previousSignerSnapshot
                ).mapLeft { compensationError ->
                    LocalMCPServerConfigSyncError.CompensationFailed(
                        operation = "restore-updated-server",
                        syncError = syncError,
                        compensationError = compensationError
                    )
                }.bind()

                // Best-effort worker restoration happens after DB restoration because persisted state is authoritative.
                restorePreviousWorkerState(
                    restoredSignedServer = restoredSignerSnapshot.toWorkerSignedServerDto(),
                    failedUpdatedWorkerId = updatedServer.workerId,
                    syncError = syncError
                )

                raise(LocalMCPServerConfigSyncError.WorkerSyncFailed(syncError))
            }
        }
    }

    override suspend fun deleteServer(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerConfigSyncError, Unit> = either {
        val existingServer = withError(::toServerOperationFailed) {
            localMCPServerService.getServerById(userId, serverId).bind()
        }

        withError(::toServerOperationFailed) {
            localMCPServerService.deleteServer(userId, serverId).bind()
        }

        withError(::toWorkerSyncFailed) {
            localMCPServerWorkerSyncService.syncDeleted(workerId = existingServer.workerId, serverId = serverId).bind()
        }
    }

    /**
     * Tries to restore worker cache state after persistence has already been restored successfully.
     *
     * This step is intentionally best-effort because the database is the authoritative source of truth.
     * A failure here is logged for diagnostics, while the original worker-sync failure remains the caller-visible error.
     *
     * @param restoredSignedServer Snapshot that should become current on the worker after compensation.
     * @param failedUpdatedWorkerId Worker that was targeted by the failed updated snapshot.
     * @param syncError Original worker-sync failure that triggered compensation.
     */
    private suspend fun restorePreviousWorkerState(
        restoredSignedServer: SignedLocalMCPServerDto,
        failedUpdatedWorkerId: Long,
        syncError: LocalMCPRuntimeCommandDispatchError
    ) {
        val signedRequest = restoredSignedServer.signedRequest
        if (signedRequest == null) {
            logger.warn(
                "Skipping worker-state restoration for MCP server {} after sync failure {} because no signed snapshot is available",
                restoredSignedServer.server.id,
                syncError.javaClass.simpleName
            )
            return
        }

        if (failedUpdatedWorkerId == restoredSignedServer.server.workerId) {
            localMCPServerWorkerSyncService.syncUpdated(
                signedServer = restoredSignedServer,
                previousWorkerId = failedUpdatedWorkerId
            ).fold(
                ifLeft = { restorationError ->
                    logger.warn(
                        "Failed to restore worker cache for MCP server {} after sync failure {}: {}",
                        restoredSignedServer.server.id,
                        syncError.javaClass.simpleName,
                        restorationError
                    )
                },
                ifRight = {}
            )
            return
        }

        // Reassignment rollback should try to recreate the original worker state even when cleanup on the failed target worker also fails.
        localMCPServerWorkerSyncService.syncDeleted(
            workerId = failedUpdatedWorkerId,
            serverId = restoredSignedServer.server.id
        ).fold(
            ifLeft = { cleanupError ->
                logger.warn(
                    "Failed to clean up worker {} while restoring MCP server {} after sync failure {}: {}",
                    failedUpdatedWorkerId,
                    restoredSignedServer.server.id,
                    syncError.javaClass.simpleName,
                    cleanupError
                )
            },
            ifRight = {}
        )

        localMCPServerWorkerSyncService.syncCreated(restoredSignedServer).fold(
            ifLeft = { restorationError ->
                logger.warn(
                    "Failed to recreate original worker cache for MCP server {} after sync failure {}: {}",
                    restoredSignedServer.server.id,
                    syncError.javaClass.simpleName,
                    restorationError
                )
            },
            ifRight = {}
        )
    }

    /**
     * Converts a signer-scoped compensation snapshot into the shared worker transport shape.
     *
     * Worker synchronization only needs the server DTO plus optional detached request, while compensation
     * also carries signer identity for unsigned snapshots. The explicit mapping keeps those concerns separate.
     *
     * @receiver Signer-scoped compensation snapshot.
     * @return Worker-facing signed server DTO.
     */
    private fun LocalMCPServerSignerSnapshot.toWorkerSignedServerDto(): SignedLocalMCPServerDto = SignedLocalMCPServerDto(
        server = server,
        signedRequest = signedRequest
    )

    /**
     * Wraps Local MCP persistence errors as config-sync orchestration failures.
     *
     * @param error Underlying persistence error.
     * @return Wrapped orchestration error.
     */
    private fun toServerOperationFailed(
        error: LocalMCPServerServiceError
    ): LocalMCPServerConfigSyncError = LocalMCPServerConfigSyncError.ServerOperationFailed(error)

    /**
     * Wraps low-level worker-sync failures as config-sync orchestration failures.
     *
     * @param error Underlying worker-sync failure.
     * @return Wrapped orchestration error.
     */
    private fun toWorkerSyncFailed(
        error: LocalMCPRuntimeCommandDispatchError
    ): LocalMCPServerConfigSyncError = LocalMCPServerConfigSyncError.WorkerSyncFailed(error)

    companion object {
        /**
         * Logger used for Local MCP worker-sync orchestration diagnostics.
         */
        private val logger: Logger = LogManager.getLogger(DefaultLocalMCPServerConfigSyncService::class.java)
    }
}


