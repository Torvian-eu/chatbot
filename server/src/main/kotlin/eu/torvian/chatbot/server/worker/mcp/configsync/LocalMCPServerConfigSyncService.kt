package eu.torvian.chatbot.server.worker.mcp.configsync

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest
import eu.torvian.chatbot.common.security.SignedRequest

/**
 * Coordinates Local MCP server write operations together with worker cache synchronization.
 *
 * The HTTP layer delegates create, update, and delete orchestration here so routes remain
 * transport-focused while this contract owns the worker-sync and compensation policy.
 */
interface LocalMCPServerConfigSyncService {
    /**
     * Creates a signed Local MCP server and synchronizes it to the assigned worker.
     *
     * If worker synchronization fails after persistence succeeds, the implementation must attempt
     * to delete the newly created server as compensation before returning the sync failure.
     *
     * @param userId Authenticated user performing the mutation.
     * @param request Create payload to persist.
     * @param signedRequest Detached signing metadata for the exact raw request body.
     * @return Either orchestration error or the created Local MCP server DTO.
     */
    suspend fun createSignedServer(
        userId: Long,
        request: CreateLocalMCPServerRequest,
        signedRequest: SignedRequest
    ): Either<LocalMCPServerConfigSyncError, LocalMCPServerDto>

    /**
     * Updates a signed Local MCP server and synchronizes the mutation to the relevant worker caches.
     *
     * If worker synchronization fails after persistence succeeds, the implementation must attempt
     * to restore the previously persisted configuration before returning the sync failure.
     *
     * @param userId Authenticated user performing the mutation.
     * @param serverId Server identifier being updated.
     * @param request Update payload to persist.
     * @param signedRequest Detached signing metadata for the exact raw request body.
     * @return Either orchestration error or the updated Local MCP server DTO.
     */
    suspend fun updateSignedServer(
        userId: Long,
        serverId: Long,
        request: UpdateLocalMCPServerRequest,
        signedRequest: SignedRequest
    ): Either<LocalMCPServerConfigSyncError, LocalMCPServerDto>

    /**
     * Deletes a Local MCP server and propagates the removal to the assigned worker cache.
     *
     * @param userId Authenticated user performing the mutation.
     * @param serverId Server identifier to delete.
     * @return Either orchestration error or Unit when the delete flow completes.
     */
    suspend fun deleteServer(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerConfigSyncError, Unit>
}

