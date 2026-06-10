package eu.torvian.chatbot.server.worker.mcp.configsync

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPServerDto
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchError

/**
 * Low-level adapter that forwards persisted Local MCP server configurations to worker cache commands.
 *
 * This contract does not perform persistence or compensation. It only expresses worker synchronization
 * outcomes as typed Either results so higher-level orchestration can decide how to react.
 */
interface LocalMCPServerWorkerSyncService {
    /**
     * Pushes a newly created Local MCP server configuration to the assigned worker cache.
     *
     * @param signedServer Newly created Local MCP server configuration plus detached signed-request metadata.
     * @return Either worker-sync error or Unit when the worker accepted the create.
     */
    suspend fun syncCreated(
        signedServer: SignedLocalMCPServerDto
    ): Either<LocalMCPRuntimeCommandDispatchError, Unit>

    /**
     * Pushes an updated Local MCP server configuration to the assigned worker cache.
     *
     * Same-worker updates are forwarded as in-place worker updates. When the worker changes,
     * the stale cache entry is removed from the previous worker before creating the server on the new worker.
     *
     * @param signedServer Updated Local MCP server configuration plus detached signed-request metadata.
     * @param previousWorkerId Worker identifier that owned the server before the update.
     * @return Either worker-sync error or Unit when the worker sync completes.
     */
    suspend fun syncUpdated(
        signedServer: SignedLocalMCPServerDto,
        previousWorkerId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, Unit>

    /**
     * Removes a deleted Local MCP server configuration from the assigned worker cache.
     *
     * @param workerId Worker identifier that previously owned the server configuration.
     * @param serverId Local MCP server identifier to remove.
     * @return Either worker-sync error or Unit when the worker accepted the delete.
     */
    suspend fun syncDeleted(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, Unit>
}