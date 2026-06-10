package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.mcp.*
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPServerError
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.data.entities.*
import eu.torvian.chatbot.server.service.core.LocalMCPServerSignerSnapshot
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.*
import eu.torvian.chatbot.server.service.security.CredentialManager
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock

/**
 * Implementation of the [LocalMCPServerService] interface.
 *
 * This service enforces worker ownership, persists full Local MCP configuration,
 * and mediates secure secret alias lifecycle via [CredentialManager].
 *
 * @property localMCPServerDao Persistence gateway for normalized server configuration.
 * @property localMCPServerSignatureDao Persistence gateway for signed configuration snapshots.
 * @property localMCPToolDefinitionDao Persistence gateway for tool links that depend on servers.
 * @property userDeviceDao Registry lookup used to bind detached-request signers to authenticated users.
 * @property workerDao Persistence gateway for validating worker ownership.
 * @property credentialManager Secret storage service used for plaintext secret environment variables.
 * @property transactionScope Transaction boundary used to keep config and signature writes atomic.
 */
class LocalMCPServerServiceImpl(
    private val localMCPServerDao: LocalMCPServerDao,
    private val localMCPServerSignatureDao: LocalMCPServerSignatureDao,
    private val localMCPToolDefinitionDao: LocalMCPToolDefinitionDao,
    private val userDeviceDao: UserDeviceDao,
    private val workerDao: WorkerDao,
    private val credentialManager: CredentialManager,
    private val transactionScope: TransactionScope,
) : LocalMCPServerService {

    private val logger: Logger = LogManager.getLogger(LocalMCPServerServiceImpl::class.java)

    override suspend fun createSignedServer(
        userId: Long,
        request: CreateLocalMCPServerRequest,
        signedRequest: SignedRequest
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto> =
        transactionScope.transaction {
            either {
                val signerDevice = resolveSignerDevice(userId, signedRequest.signerId).bind()
                val createdServer = createServerInTransaction(userId, request).bind()
                storeSignatureSnapshot(createdServer.id, signerDevice.id, signedRequest)
                createdServer
            }
        }


    override suspend fun getServersByUserId(userId: Long): Either<LocalMCPServerServiceError, List<LocalMCPServerDto>> =
        transactionScope.transaction {
            either {
                localMCPServerDao.getServersByUserId(userId)
                    .map { it.toDto(resolveSecretEnvironmentVariables(it).bind()) }
            }
        }


    override suspend fun getServerById(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto> =
        transactionScope.transaction {
            loadServerDtoForUserInTransaction(userId, serverId)
        }

    override suspend fun getServerSignerSnapshot(
        userId: Long,
        serverId: Long,
        signerId: String
    ): Either<LocalMCPServerServiceError, LocalMCPServerSignerSnapshot> =
        transactionScope.transaction {
            resolveServerSignerSnapshotInTransaction(
                userId = userId,
                serverId = serverId,
                signerId = signerId
            )
        }

    override suspend fun updateSignedServer(
        userId: Long,
        serverId: Long,
        request: UpdateLocalMCPServerRequest,
        signedRequest: SignedRequest
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto> =
        transactionScope.transaction {
            either {
                val signerDevice = resolveSignerDevice(userId, signedRequest.signerId).bind()
                val updatedServer = updateServerInTransaction(userId, serverId, request).bind()
                storeSignatureSnapshot(updatedServer.id, signerDevice.id, signedRequest)
                updatedServer
            }
        }

    override suspend fun restoreServerSnapshot(
        userId: Long,
        serverId: Long,
        snapshot: LocalMCPServerSignerSnapshot
    ): Either<LocalMCPServerServiceError, LocalMCPServerSignerSnapshot> =
        transactionScope.transaction {
            restoreServerSnapshotInTransaction(
                userId = userId,
                serverId = serverId,
                snapshot = snapshot
            )
        }

    override suspend fun deleteServer(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerServiceError, Unit> =
        transactionScope.transaction {
            either {
                val existingEntity = withError({ error: LocalMCPServerError.Unauthorized ->
                    LocalMCPServerUnauthorizedError(error.userId, error.serverId)
                }) {
                    localMCPServerDao.getServerByIdForUser(userId, serverId).bind()
                }

                val deletedCount = localMCPToolDefinitionDao.deleteToolsByServerId(serverId)
                logger.info("Deleted $deletedCount tools for MCP server $serverId")

                withError({ daoError ->
                    when (daoError) {
                        is DeleteLocalMCPServerError.NotFound -> LocalMCPServerNotFoundError(daoError.id)
                    }
                }) {
                    localMCPServerDao.deleteById(serverId).bind()
                    logger.info("Deleted MCP server $serverId")
                }

                cleanupSecretAliases(existingEntity.secretEnvironmentVariables.map { it.alias })
            }
        }


    override suspend fun getSignedServersByWorkerId(
        workerId: Long
    ): Either<LocalMCPServerServiceError, List<SignedLocalMCPServerDto>> =
        transactionScope.transaction {
            either {
                localMCPServerDao.getServersByWorkerId(workerId).map { entity ->
                    SignedLocalMCPServerDto(
                        server = entity.toDto(resolveSecretEnvironmentVariables(entity).bind()),
                        signedRequest = resolveLatestSignedRequest(entity.id)
                    )
                }
            }
        }

    override suspend fun validateOwnership(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerServiceError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: LocalMCPServerError.Unauthorized ->
                    LocalMCPServerUnauthorizedError(daoError.userId, daoError.serverId)
                }) {
                    localMCPServerDao.validateOwnership(userId, serverId).bind()
                    logger.debug("Validated ownership of server $serverId for user $userId")
                }
            }
        }

    override suspend fun validateWorkerOwnership(
        userId: Long,
        workerId: Long
    ): Either<LocalMCPServerServiceError, Unit> =
        either {
            val worker = withError({ workerError: WorkerError.NotFound ->
                LocalMCPServerWorkerNotFoundError(workerError.workerId)
            }) {
                workerDao.getWorkerById(workerId).bind()
            }

            ensure(worker.ownerUserId == userId) {
                LocalMCPServerWorkerOwnershipMismatchError(userId, workerId, worker.ownerUserId)
            }
        }

    /**
     * Validates the request shape for full Local MCP server configuration updates.
     *
     * @param name Proposed Local MCP server display name.
     * @param command Proposed process command.
     * @return Either validation error or Unit.
     */
    private fun validateRequest(name: String, command: String): Either<LocalMCPServerServiceError, Unit> = either {
        ensure(name.isNotBlank()) { LocalMCPServerValidationError("name must not be blank") }
        ensure(command.isNotBlank()) { LocalMCPServerValidationError("command must not be blank") }
    }

    /**
     * Creates normalized server configuration inside an already established transaction boundary.
     *
     * @param userId Owning user identifier.
     * @param request Decoded and typed create request.
     * @return Either service error or created server DTO with plaintext secret values restored for the caller.
     */
    private suspend fun createServerInTransaction(
        userId: Long,
        request: CreateLocalMCPServerRequest
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto> = either {
        validateRequest(request.name, request.command).bind()
        validateWorkerOwnership(userId, request.workerId).bind()
        val secretReferences = storeSecretEnvironmentVariables(request.secretEnvironmentVariables).bind()
        val createdEntity = localMCPServerDao.createServer(
            CreateLocalMCPServerEntity(
                userId = userId,
                workerId = request.workerId,
                name = request.name,
                description = request.description,
                command = request.command,
                arguments = request.arguments,
                workingDirectory = request.workingDirectory,
                isEnabled = request.isEnabled,
                autoStartOnEnable = request.autoStartOnEnable,
                autoStartOnLaunch = request.autoStartOnLaunch,
                autoStopAfterInactivitySeconds = request.autoStopAfterInactivitySeconds,
                toolNamePrefix = request.toolNamePrefix,
                environmentVariables = request.environmentVariables,
                secretEnvironmentVariables = secretReferences
            )
        )

        createdEntity.toDto(request.secretEnvironmentVariables)
    }

    /**
     * Updates normalized server configuration inside an already established transaction boundary.
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @param request Decoded and typed update request.
     * @return Either service error or updated server DTO with plaintext secret values restored for the caller.
     */
    private suspend fun updateServerInTransaction(
        userId: Long,
        serverId: Long,
        request: UpdateLocalMCPServerRequest
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto> = either {
        val newSecretReferences = storeSecretEnvironmentVariables(request.secretEnvironmentVariables).bind()
        persistUpdatedServerInTransaction(
            userId = userId,
            serverId = serverId,
            server = request.toUpdateEntity(newSecretReferences),
            resolvedSecretEnvironmentVariables = request.secretEnvironmentVariables
        ).bind()
    }

    /**
     * Loads one user-owned Local MCP server DTO inside an already established transaction boundary.
     *
     * This helper centralizes ownership checks and secret resolution so direct reads and signer-scoped
     * snapshot reconstruction both use the same normalized persistence view.
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @return Either service error or the resolved server DTO.
     */
    private suspend fun loadServerDtoForUserInTransaction(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto> = either {
        val entity = withError({ error: LocalMCPServerError.Unauthorized ->
            LocalMCPServerUnauthorizedError(error.userId, error.serverId)
        }) {
            localMCPServerDao.getServerByIdForUser(userId, serverId).bind()
        }
        entity.toDto(resolveSecretEnvironmentVariables(entity).bind())
    }

    /**
     * Resolves the currently persisted Local MCP state for one server and one signer inside a transaction.
     *
     * The returned snapshot is signer-scoped instead of worker-scoped so compensation can represent
     * "unsigned for signer X" without scanning unrelated signature rows. When the signer device row no longer
     * exists, the snapshot is returned as unsigned while preserving the requested `signerId`.
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @param signerId Client-side signer identifier whose detached signature row should be read.
     * @return Either service error or the signer-scoped compensation snapshot.
     */
    private suspend fun resolveServerSignerSnapshotInTransaction(
        userId: Long,
        serverId: Long,
        signerId: String
    ): Either<LocalMCPServerServiceError, LocalMCPServerSignerSnapshot> = either {
        val server = loadServerDtoForUserInTransaction(userId, serverId).bind()
        val signerDevice = userDeviceDao.getDeviceByClientId(userId, signerId)
        if (signerDevice == null) {
            // Compensation still needs to remember which signer was being updated even when the device row vanished.
            LocalMCPServerSignerSnapshot(server = server, signerId = signerId, signedRequest = null)
        } else {
            val signedRequest = localMCPServerSignatureDao.getSignature(serverId, signerDevice.id)
                ?.toSignedRequest(signerId)
            LocalMCPServerSignerSnapshot(server = server, signerId = signerId, signedRequest = signedRequest)
        }
    }

    /**
     * Restores a persisted Local MCP snapshot inside an already established transaction boundary.
     *
     * The snapshot is treated as authoritative persisted state, so normalized configuration is
     * restored directly from the DTO and the signer-specific signature row is reconciled afterward.
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @param snapshot Previously persisted signer-scoped snapshot to restore.
     * @return Either service error or the actual restored snapshot result.
     */
    private suspend fun restoreServerSnapshotInTransaction(
        userId: Long,
        serverId: Long,
        snapshot: LocalMCPServerSignerSnapshot
    ): Either<LocalMCPServerServiceError, LocalMCPServerSignerSnapshot> = either {
        val restoredSecretReferences = storeSecretEnvironmentVariables(snapshot.server.secretEnvironmentVariables).bind()
        val restoredServer = persistUpdatedServerInTransaction(
            userId = userId,
            serverId = serverId,
            server = snapshot.server.toUpdateEntity(restoredSecretReferences),
            resolvedSecretEnvironmentVariables = snapshot.server.secretEnvironmentVariables
        ).bind()
        val restoredSignedRequest = restoreSignatureSnapshot(
            userId = userId,
            serverId = serverId,
            snapshot = snapshot
        )

        LocalMCPServerSignerSnapshot(
            server = restoredServer,
            signerId = snapshot.signerId,
            signedRequest = restoredSignedRequest
        )
    }

    /**
     * Persists a fully normalized Local MCP update inside an already established transaction boundary.
     *
     * This helper is shared by typed request updates and snapshot restoration so both paths reuse
     * the same ownership checks, DAO update call, and secret-alias cleanup semantics.
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @param server Normalized persistence payload to write.
     * @param resolvedSecretEnvironmentVariables Plaintext secret values that should be returned to callers.
     * @return Either service error or updated server DTO.
     */
    private suspend fun persistUpdatedServerInTransaction(
        userId: Long,
        serverId: Long,
        server: UpdateLocalMCPServerEntity,
        resolvedSecretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto>
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto> = either {
        validateRequest(server.name, server.command).bind()
        validateWorkerOwnership(userId, server.workerId).bind()

        val existingEntity = withError({ error: LocalMCPServerError.Unauthorized ->
            LocalMCPServerUnauthorizedError(error.userId, error.serverId)
        }) {
            localMCPServerDao.getServerByIdForUser(userId, serverId).bind()
        }

        val updatedEntity = withError({ error: LocalMCPServerError.Unauthorized ->
            LocalMCPServerUnauthorizedError(error.userId, error.serverId)
        }) {
            localMCPServerDao.updateServer(
                userId = userId,
                serverId = serverId,
                server = server
            ).bind()
        }

        cleanupSecretAliases(existingEntity.secretEnvironmentVariables.map { it.alias })
        updatedEntity.toDto(resolvedSecretEnvironmentVariables)
    }

    /**
     * Resolves the detached-request signer to an internal user-device row owned by the authenticated user.
     *
     * @param userId Authenticated user identifier.
     * @param signerId Client-side signer identifier provided by detached signing metadata.
     * @return Either unknown-device error or the matching user-device row.
     */
    private suspend fun resolveSignerDevice(
        userId: Long,
        signerId: String
    ): Either<LocalMCPServerServiceError, UserDeviceEntity> = either {
        userDeviceDao.getDeviceByClientId(userId, signerId)
            ?: raise(LocalMCPServerUnknownSignerDeviceError(userId, signerId))
    }

    /**
     * Stores a signature snapshot for the exact signed Local MCP payload without verifying the signature bytes.
     *
     * @param serverId Local MCP server identifier linked to the signed configuration.
     * @param userDeviceId Internal user-device row that produced the signature.
     * @param signedRequest Detached request-signing metadata whose payload and headers must be persisted for worker relay.
     */
    private suspend fun storeSignatureSnapshot(serverId: Long, userDeviceId: Long, signedRequest: SignedRequest) {
        val now = Clock.System.now()
        localMCPServerSignatureDao.upsertSignature(
            LocalMCPServerSignatureEntity(
                serverId = serverId,
                userDeviceId = userDeviceId,
                signature = signedRequest.signature,
                timestamp = signedRequest.timestamp,
                nonce = signedRequest.nonce,
                payloadJson = signedRequest.payload,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    /**
     * Reconciles the signer-scoped persisted signature row while restoring a previous Local MCP snapshot.
     *
     * Signature persistence is keyed by `(serverId, userDeviceId)`, so compensation must only mutate the
     * row for the signer represented by the compensation snapshot. When the snapshot is signed, that row is
     * restored from the stored detached request. When the snapshot is unsigned, the signer row is deleted.
     *
     * @param userId Authenticated user performing the compensation.
     * @param serverId Target server identifier.
     * @param snapshot Previously persisted signer-scoped compensation snapshot.
     * @return The restored detached signature snapshot, or null when the restored state is unsigned.
     */
    private suspend fun restoreSignatureSnapshot(
        userId: Long,
        serverId: Long,
        snapshot: LocalMCPServerSignerSnapshot
    ): SignedRequest? {
        val snapshotSignedRequest = snapshot.signedRequest
        val signerDevice = userDeviceDao.getDeviceByClientId(userId, snapshot.signerId)
        if (signerDevice == null) {
            logger.warn(
                "Skipping signer-scoped MCP signature {} for server {} because signer device {} no longer exists for user {}",
                if (snapshotSignedRequest == null) "rollback" else "restoration",
                serverId,
                snapshot.signerId,
                userId
            )
            return null
        }

        if (snapshotSignedRequest == null) {
            localMCPServerSignatureDao.deleteSignature(
                serverId = serverId,
                userDeviceId = signerDevice.id
            )
            return null
        }

        storeSignatureSnapshot(
            serverId = serverId,
            userDeviceId = signerDevice.id,
            signedRequest = snapshotSignedRequest
        )
        return snapshotSignedRequest
    }

    /**
     * Resolves the latest detached signed snapshot for one server into worker-facing transport shape.
     *
     * The newest signature row is treated as authoritative because older per-device snapshots may describe
     * historical config versions that are no longer current after later updates.
     *
     * @param serverId Local MCP server identifier whose latest signature snapshot should be relayed.
     * @return Detached signed request snapshot, or null when no current snapshot can be reconstructed.
     */
    private suspend fun resolveLatestSignedRequest(serverId: Long): SignedRequest? {
        val signatureEntity = localMCPServerSignatureDao.getSignaturesByServerId(serverId)
            .maxByOrNull { it.updatedAt }
            ?: run {
                logger.warn("No signed MCP snapshot found for server {}", serverId)
                return null
            }

        val signerDevice = userDeviceDao.getDeviceById(signatureEntity.userDeviceId)
        if (signerDevice == null) {
            logger.warn(
                "Failed to reconstruct signed MCP config snapshot for server {} because signer device {} no longer exists",
                serverId,
                signatureEntity.userDeviceId
            )
            return null
        }

        return signatureEntity.toSignedRequest(signerDevice.clientDeviceId)
    }

    /**
     * Reconstructs detached request-signing metadata from one persisted signature row.
     *
     * @receiver Persisted signature entity for a specific Local MCP signer row.
     * @param signerId Client-side signer identifier that owns the row.
     * @return Detached signed request reconstructed from persistence.
     */
    private fun LocalMCPServerSignatureEntity.toSignedRequest(signerId: String): SignedRequest = SignedRequest(
        payload = payloadJson,
        signature = signature,
        signerId = signerId,
        timestamp = timestamp,
        nonce = nonce
    )

    /**
     * Stores plaintext secret environment variables and returns alias references.
     *
     * @param variables Secret variables with plaintext values.
     * @return Either storage error or secret alias references for persistence.
     */
    private suspend fun storeSecretEnvironmentVariables(
        variables: List<LocalMCPEnvironmentVariableDto>
    ): Either<LocalMCPServerServiceError, List<LocalMCPSecretEnvironmentVariableReference>> =
        either {
            val storedAliases = mutableListOf<String>()
            val references = mutableListOf<LocalMCPSecretEnvironmentVariableReference>()

            try {
                variables.forEach { variable ->
                    val alias = credentialManager.storeCredential(variable.value)
                    storedAliases += alias
                    references += LocalMCPSecretEnvironmentVariableReference(variable.key, alias)
                }
            } catch (_: Throwable) {
                cleanupSecretAliases(storedAliases)
                raise(LocalMCPServerSecretStorageError(variableKey = variables.firstOrNull()?.key ?: "unknown"))
            }

            references
        }

    /**
     * Resolves persisted secret aliases to plaintext values.
     *
     * @param entity Persisted entity containing secret alias metadata.
     * @return Either resolution error or resolved secret environment variables.
     */
    private suspend fun resolveSecretEnvironmentVariables(
        entity: LocalMCPServerEntity
    ): Either<LocalMCPServerServiceError, List<LocalMCPEnvironmentVariableDto>> =
        either {
            entity.secretEnvironmentVariables.map { secretReference ->
                val secretValue = withError({
                    LocalMCPServerSecretResolutionError(secretReference.key, secretReference.alias)
                }) {
                    credentialManager.getCredential(secretReference.alias).bind()
                }

                LocalMCPEnvironmentVariableDto(
                    key = secretReference.key,
                    value = secretValue
                )
            }
        }

    /**
     * Deletes credential aliases and logs cleanup failures without failing the caller.
     *
     * @param aliases Credential aliases to remove.
     */
    private suspend fun cleanupSecretAliases(aliases: List<String>) {
        aliases.forEach { alias ->
            credentialManager.deleteCredential(alias).fold(
                ifLeft = {
                    logger.warn("Failed to delete credential alias '{}' during Local MCP cleanup", alias)
                },
                ifRight = {}
            )
        }
    }

    /**
     * Converts a persisted Local MCP entity to API DTO with resolved secret values.
     *
     * @receiver Persisted Local MCP entity.
     * @param resolvedSecretEnvironmentVariables Secret environment variables in plaintext.
     * @return API DTO representation.
     */
    private fun LocalMCPServerEntity.toDto(
        resolvedSecretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto>
    ): LocalMCPServerDto = LocalMCPServerDto(
        id = id,
        userId = userId,
        workerId = workerId,
        name = name,
        description = description,
        command = command,
        arguments = arguments,
        workingDirectory = workingDirectory,
        isEnabled = isEnabled,
        autoStartOnEnable = autoStartOnEnable,
        autoStartOnLaunch = autoStartOnLaunch,
        autoStopAfterInactivitySeconds = autoStopAfterInactivitySeconds,
        toolNamePrefix = toolNamePrefix,
        environmentVariables = environmentVariables,
        secretEnvironmentVariables = resolvedSecretEnvironmentVariables,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    /**
     * Converts a typed Local MCP update request into the normalized DAO payload.
     *
     * @receiver Parsed update request.
     * @param secretEnvironmentVariables Secret alias references prepared for persistence.
     * @return Normalized Local MCP update payload.
     */
    private fun UpdateLocalMCPServerRequest.toUpdateEntity(
        secretEnvironmentVariables: List<LocalMCPSecretEnvironmentVariableReference>
    ): UpdateLocalMCPServerEntity = UpdateLocalMCPServerEntity(
        workerId = workerId,
        name = name,
        description = description,
        command = command,
        arguments = arguments,
        workingDirectory = workingDirectory,
        isEnabled = isEnabled,
        autoStartOnEnable = autoStartOnEnable,
        autoStartOnLaunch = autoStartOnLaunch,
        autoStopAfterInactivitySeconds = autoStopAfterInactivitySeconds,
        toolNamePrefix = toolNamePrefix,
        environmentVariables = environmentVariables,
        secretEnvironmentVariables = secretEnvironmentVariables
    )

    /**
     * Converts a persisted Local MCP DTO snapshot into the normalized DAO payload for restoration.
     *
     * @receiver Persisted Local MCP DTO snapshot whose mutable fields should be restored.
     * @param secretEnvironmentVariables Secret alias references prepared for persistence.
     * @return Normalized Local MCP update payload.
     */
    private fun LocalMCPServerDto.toUpdateEntity(
        secretEnvironmentVariables: List<LocalMCPSecretEnvironmentVariableReference>
    ): UpdateLocalMCPServerEntity = UpdateLocalMCPServerEntity(
        workerId = workerId,
        name = name,
        description = description,
        command = command,
        arguments = arguments,
        workingDirectory = workingDirectory,
        isEnabled = isEnabled,
        autoStartOnEnable = autoStartOnEnable,
        autoStartOnLaunch = autoStartOnLaunch,
        autoStopAfterInactivitySeconds = autoStopAfterInactivitySeconds,
        toolNamePrefix = toolNamePrefix,
        environmentVariables = environmentVariables,
        secretEnvironmentVariables = secretEnvironmentVariables
    )
}
