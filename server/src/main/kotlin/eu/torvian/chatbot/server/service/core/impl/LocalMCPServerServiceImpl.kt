package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.LocalMCPToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPServerError
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.data.entities.CreateLocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.LocalMCPSecretEnvironmentVariableReference
import eu.torvian.chatbot.server.data.entities.LocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.UpdateLocalMCPServerEntity
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerNotFoundError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerSecretResolutionError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerSecretStorageError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerServiceError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerUnauthorizedError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerValidationError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerWorkerNotFoundError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerWorkerOwnershipMismatchError
import eu.torvian.chatbot.server.service.security.CredentialManager
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of the [LocalMCPServerService] interface.
 *
 * This service enforces worker ownership, persists full Local MCP configuration,
 * and mediates secure secret alias lifecycle via [CredentialManager].
 */
class LocalMCPServerServiceImpl(
    private val localMCPServerDao: LocalMCPServerDao,
    private val localMCPToolDefinitionDao: LocalMCPToolDefinitionDao,
    private val workerDao: WorkerDao,
    private val credentialManager: CredentialManager,
    private val transactionScope: TransactionScope,
) : LocalMCPServerService {

    private val logger: Logger = LogManager.getLogger(LocalMCPServerServiceImpl::class.java)

    override suspend fun createServer(
        userId: Long,
        request: CreateLocalMCPServerRequest
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto> =
        transactionScope.transaction {
            either {
                validateRequest(request.name, request.command).bind()
                validateWorkerAssignment(userId, request.workerId).bind()
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
        }


    override suspend fun getServersByUserId(userId: Long): Either<LocalMCPServerServiceError, List<LocalMCPServerDto>> =
        transactionScope.transaction {
            either {
                localMCPServerDao.getServersByUserId(userId).map { it.toDto(resolveSecretEnvironmentVariables(it).bind()) }
            }
        }


    override suspend fun getServerById(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto> =
        transactionScope.transaction {
            either {
                val entity = withError({ error: LocalMCPServerError.Unauthorized ->
                    LocalMCPServerUnauthorizedError(error.userId, error.serverId)
                }) {
                    localMCPServerDao.getServerByIdForUser(userId, serverId).bind()
                }
                entity.toDto(resolveSecretEnvironmentVariables(entity).bind())
            }
        }

    override suspend fun updateServer(
        userId: Long,
        serverId: Long,
        request: UpdateLocalMCPServerRequest
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto> =
        transactionScope.transaction {
            either {
                validateRequest(request.name, request.command).bind()
                validateWorkerAssignment(userId, request.workerId).bind()

                val existingEntity = withError({ error: LocalMCPServerError.Unauthorized ->
                    LocalMCPServerUnauthorizedError(error.userId, error.serverId)
                }) {
                    localMCPServerDao.getServerByIdForUser(userId, serverId).bind()
                }

                val newSecretReferences = storeSecretEnvironmentVariables(request.secretEnvironmentVariables).bind()
                val updatedEntity = withError({ error: LocalMCPServerError.Unauthorized ->
                    LocalMCPServerUnauthorizedError(error.userId, error.serverId)
                }) {
                    localMCPServerDao.updateServer(
                        userId = userId,
                        serverId = serverId,
                        server = UpdateLocalMCPServerEntity(
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
                            secretEnvironmentVariables = newSecretReferences
                        )
                    ).bind()
                }

                cleanupSecretAliases(existingEntity.secretEnvironmentVariables.map { it.alias })
                updatedEntity.toDto(request.secretEnvironmentVariables)
            }
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

    override suspend fun getServersByWorkerId(workerId: Long): Either<LocalMCPServerServiceError, List<LocalMCPServerDto>> =
        transactionScope.transaction {
            either {
                localMCPServerDao.getServersByWorkerId(workerId).map { it.toDto(resolveSecretEnvironmentVariables(it).bind()) }
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

    @Deprecated("Use getServersByUserId")
    override suspend fun getServerIdsByUserId(userId: Long): List<Long> =
        localMCPServerDao.getIdsByUserId(userId)

    @Deprecated("Use updateServer")
    override suspend fun setServerEnabled(
        serverId: Long,
        isEnabled: Boolean
    ): Either<LocalMCPServerServiceError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: LocalMCPServerError.NotFound ->
                    LocalMCPServerNotFoundError(daoError.id)
                }) {
                    localMCPServerDao.setEnabled(serverId, isEnabled).bind()
                    logger.debug("Updated enabled state of server $serverId to $isEnabled")
                }
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
     * Ensures the assigned worker exists and is owned by the requesting user.
     *
     * @param userId Requesting user identifier.
     * @param workerId Requested worker assignment.
     * @return Either assignment error or Unit.
     */
    private suspend fun validateWorkerAssignment(
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
}
