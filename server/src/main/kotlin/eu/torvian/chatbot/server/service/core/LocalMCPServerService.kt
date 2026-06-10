package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerServiceError

/**
 * Service interface for full Local MCP server configuration management.
 */
interface LocalMCPServerService {
    /**
     * Creates a Local MCP server from a detached signed request and stores the signature snapshot atomically.
     *
     * @param userId Owning user identifier.
     * @param request Parsed create request body.
     * @param signedRequest Detached signing metadata plus the exact raw request body string.
     * @return Either service error or created server payload.
     */
    suspend fun createSignedServer(
        userId: Long,
        request: CreateLocalMCPServerRequest,
        signedRequest: SignedRequest
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto>

    /**
     * Lists all Local MCP servers owned by a user.
     *
     * @param userId Owning user identifier.
     * @return Either service error or user-owned server list.
     */
    suspend fun getServersByUserId(userId: Long): Either<LocalMCPServerServiceError, List<LocalMCPServerDto>>

    /**
     * Fetches a specific user-owned Local MCP server.
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @return Either service error or matching server payload.
     */
    suspend fun getServerById(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto>

    /**
     * Updates a Local MCP server from a detached signed request and stores the signature snapshot atomically.
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @param request Parsed update request body.
     * @param signedRequest Detached signing metadata plus the exact raw request body string.
     * @return Either service error or updated server payload.
     */
    suspend fun updateSignedServer(
        userId: Long,
        serverId: Long,
        request: UpdateLocalMCPServerRequest,
        signedRequest: SignedRequest
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto>

    /**
     * Resolves the currently persisted Local MCP state for one server and one signer identity.
     *
     * Compensation uses this signer-scoped snapshot instead of worker-facing signed DTOs because it must capture
     * both the normalized server configuration and whether a specific signer had a detached signature row.
     * If the signer device row no longer exists, the snapshot is still returned with the provided `signerId` and
     * a null detached request so the compensation contract can still represent "unsigned for signer X".
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @param signerId Client-side signer identifier whose detached signature row should be resolved.
     * @return Either service error or the signer-scoped compensation snapshot.
     */
    suspend fun getServerSignerSnapshot(
        userId: Long,
        serverId: Long,
        signerId: String
    ): Either<LocalMCPServerServiceError, LocalMCPServerSignerSnapshot>

    /**
     * Restores a previously persisted Local MCP snapshot directly at the persistence layer.
     *
     * This operation is intended for compensation flows after worker synchronization fails.
     * The implementation must restore normalized server configuration from the supplied signer-scoped snapshot,
     * then reconcile that signer's detached signature row.
     * Compensation is device-scoped because signature persistence is keyed by `(serverId, userDeviceId)`.
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @param snapshot Previously persisted signer-scoped snapshot to restore.
     * @return Either service error or the restored snapshot result.
     */
    suspend fun restoreServerSnapshot(
        userId: Long,
        serverId: Long,
        snapshot: LocalMCPServerSignerSnapshot
    ): Either<LocalMCPServerServiceError, LocalMCPServerSignerSnapshot>

    /**
     * Deletes a user-owned Local MCP server and linked tools.
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @return Either service error or Unit.
     */
    suspend fun deleteServer(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerServiceError, Unit>

    /**
     * Lists worker-assigned Local MCP servers together with their latest detached signed snapshots.
     *
     * The worker bootstrap flow needs both the persisted DTO and the exact detached authorization metadata
     * so it can re-verify trust locally before caching executable configuration.
     *
     * @param workerId Worker identifier.
     * @return Either service error or worker-assigned signed server list.
     */
    suspend fun getSignedServersByWorkerId(
        workerId: Long
    ): Either<LocalMCPServerServiceError, List<SignedLocalMCPServerDto>>

    /**
     * Validates that the given user owns the given server.
     *
     * @param userId User identifier to validate.
     * @param serverId Server identifier being checked.
     * @return Either service error or Unit when authorized.
     */
    suspend fun validateOwnership(userId: Long, serverId: Long): Either<LocalMCPServerServiceError, Unit>

    /**
     * Validates that the given user owns the given worker.
     *
     * @param userId User identifier to validate.
     * @param workerId Worker identifier being checked.
     * @return Either service error or Unit when authorized.
     */
    suspend fun validateWorkerOwnership(userId: Long, workerId: Long): Either<LocalMCPServerServiceError, Unit>
}
