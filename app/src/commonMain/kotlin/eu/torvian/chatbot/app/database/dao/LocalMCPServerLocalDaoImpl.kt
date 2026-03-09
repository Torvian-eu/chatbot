package eu.torvian.chatbot.app.database.dao

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.app.database.LocalMCPServerLocalTableQueries
import eu.torvian.chatbot.app.database.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.app.database.dao.error.GetLocalMCPServerError
import eu.torvian.chatbot.app.database.dao.error.LocalMCPServerError
import eu.torvian.chatbot.app.database.dao.error.UpdateLocalMCPServerError
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.service.misc.EncryptedSecretService
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * SQLDelight implementation of [LocalMCPServerLocalDao].
 *
 * This implementation:
 * - Uses SQLDelight for type-safe database queries
 * - Delegates encryption/decryption to EncryptedSecretService
 * - Wraps operations in TransactionScope for proper transaction management
 * - Returns Either types for comprehensive error handling
 *
 * @property queries SQLDelight generated queries for LocalMCPServerLocalTable
 * @property encryptedSecretService Service for encrypting/decrypting environment variables
 * @property transactionScope Scope for database transaction management
 * @property json JSON serializer for converting collections to/from JSON
 */
class LocalMCPServerLocalDaoImpl(
    private val queries: LocalMCPServerLocalTableQueries,
    private val encryptedSecretService: EncryptedSecretService,
    private val transactionScope: TransactionScope,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : LocalMCPServerLocalDao {

    private val logger = kmpLogger<LocalMCPServerLocalDaoImpl>()

    /**
     * Helper function to map database row to LocalMCPServer model.
     * Handles decryption of environment variables if present.
     */
    private suspend fun mapRowToModel(row: eu.torvian.chatbot.app.database.LocalMCPServerLocalTable): Either<LocalMCPServerError.DecryptionFailed, LocalMCPServer> =
        either {
            // Decrypt environment variables if they exist
            val environmentVariables = row.environmentVariablesSecretId?.let { secretId ->
                val decryptedJson = encryptedSecretService.retrieveAndDecrypt(secretId)
                    .mapLeft { error ->
                        LocalMCPServerError.DecryptionFailed(
                            secretId = secretId,
                            message = "Failed to decrypt environment variables: $error",
                            cause = null
                        )
                    }
                    .bind()

                // Parse JSON to Map<String, String>
                json.decodeFromString<Map<String, String>>(decryptedJson)
            } ?: emptyMap()

            // Parse arguments JSON array
            val arguments = json.decodeFromString<List<String>>(row.arguments)

            LocalMCPServer(
                id = row.id,
                userId = row.userId,
                name = row.name,
                description = row.description,
                command = row.command,
                arguments = arguments,
                environmentVariables = environmentVariables,
                workingDirectory = row.workingDirectory,
                isEnabled = row.isEnabled,
                autoStartOnEnable = row.autoStartOnEnable,
                autoStartOnLaunch = row.autoStartOnLaunch,
                autoStopAfterInactivitySeconds = row.autoStopAfterInactivitySeconds,
                toolNamePrefix = row.toolNamePrefix,
                createdAt = Instant.fromEpochMilliseconds(row.createdAt),
                updatedAt = Instant.fromEpochMilliseconds(row.updatedAt)
            )
        }

    /**
     * Helper function to encrypt environment variables and get the secret ID.
     * Returns null if environment variables are empty.
     */
    private suspend fun encryptEnvironmentVariables(
        envVars: Map<String, String>
    ): Either<LocalMCPServerError.EncryptionFailed, Long?> =
        either {
            if (envVars.isEmpty()) {
                null
            } else {
                val envVarsJson = json.encodeToString(envVars)
                val secretEntity = encryptedSecretService.encryptAndStore(envVarsJson)
                    .mapLeft { error ->
                        LocalMCPServerError.EncryptionFailed(
                            message = "Failed to encrypt environment variables: $error",
                            cause = null
                        )
                    }
                    .bind()
                secretEntity.id
            }
        }

    override suspend fun insert(server: LocalMCPServer): LocalMCPServer =
        transactionScope.transaction {
            // Encrypt environment variables if present
            val secretId = encryptEnvironmentVariables(server.environmentVariables)
                .getOrElse { error ->
                    throw IllegalStateException("Failed to encrypt environment variables: $error")
                }

            // Convert arguments to JSON array
            val argumentsJson = json.encodeToString(server.arguments)

            // Insert server configuration
            val rowsInserted = queries.insertServer(
                id = server.id,
                userId = server.userId,
                name = server.name,
                description = server.description,
                command = server.command,
                arguments = argumentsJson,
                environmentVariablesSecretId = secretId,
                workingDirectory = server.workingDirectory,
                isEnabled = server.isEnabled,
                autoStartOnEnable = server.autoStartOnEnable,
                autoStartOnLaunch = server.autoStartOnLaunch,
                autoStopAfterInactivitySeconds = server.autoStopAfterInactivitySeconds,
                toolNamePrefix = server.toolNamePrefix,
                createdAt = server.createdAt.toEpochMilliseconds(),
                updatedAt = server.updatedAt.toEpochMilliseconds()
            )

            if (rowsInserted != 1L) {
                throw IllegalStateException("Failed to insert LocalMCPServer: expected 1 row inserted, got $rowsInserted")
            }

            server
        }

    override suspend fun update(server: LocalMCPServer): Either<UpdateLocalMCPServerError, Unit> =
        transactionScope.execute {
            either {
                // Check if server exists
                val existingRow = queries.getById(server.id).executeAsOneOrNull()
                ensure(existingRow != null) { UpdateLocalMCPServerError.NotFound(server.id) }

                // Check for duplicate name (excluding current server)
                val nameExists = queries.existsByNameAndUserId(
                    name = server.name,
                    userId = server.userId,
                    id = server.id
                ).executeAsOne()

                ensure(!nameExists) {
                    UpdateLocalMCPServerError.DuplicateName(server.name, server.userId)
                }

                // Handle environment variables encryption
                val oldSecretId = existingRow.environmentVariablesSecretId
                val newSecretId = if (server.environmentVariables.isEmpty()) {
                    null
                } else {
                    // Encrypt new environment variables
                    encryptEnvironmentVariables(server.environmentVariables)
                        .mapLeft { error ->
                            UpdateLocalMCPServerError.EncryptionFailed(
                                error.message,
                                error.cause
                            )
                        }
                        .bind()
                }

                // Convert arguments to JSON
                val argumentsJson = json.encodeToString(server.arguments)

                // Update server configuration FIRST (this removes the FK constraint)
                val rowsUpdated = queries.updateServer(
                    name = server.name,
                    description = server.description,
                    command = server.command,
                    arguments = argumentsJson,
                    environmentVariablesSecretId = newSecretId,
                    workingDirectory = server.workingDirectory,
                    isEnabled = server.isEnabled,
                    autoStartOnEnable = server.autoStartOnEnable,
                    autoStartOnLaunch = server.autoStartOnLaunch,
                    autoStopAfterInactivitySeconds = server.autoStopAfterInactivitySeconds,
                    toolNamePrefix = server.toolNamePrefix,
                    updatedAt = server.updatedAt.toEpochMilliseconds(),
                    id = server.id
                )

                if (rowsUpdated != 1L) {
                    throw IllegalStateException("Failed to update LocalMCPServer: expected 1 row updated, got $rowsUpdated")
                }

                // Clean up old secret AFTER updating the record (now the FK constraint is gone)
                if (oldSecretId != null && oldSecretId != newSecretId) {
                    encryptedSecretService.deleteSecret(oldSecretId).mapLeft { error ->
                        throw IllegalStateException("Failed to delete old encrypted secret: $error")
                    }.bind()
                }
            }
        }

    override suspend fun delete(id: Long): Either<DeleteLocalMCPServerError, Unit> =
        transactionScope.execute {
            either {
                // Get the server to find associated secret ID
                val row = queries.getById(id).executeAsOneOrNull()
                ensure(row != null) { DeleteLocalMCPServerError.NotFound(id) }

                // Delete the server record
                val rowsDeleted = queries.deleteById(id)
                if (rowsDeleted != 1L) {
                    throw IllegalStateException("Failed to delete LocalMCPServer: expected 1 row deleted, got $rowsDeleted")
                }

                // Clean up encrypted secret if it exists
                row.environmentVariablesSecretId?.let { secretId ->
                    encryptedSecretService.deleteSecret(secretId)
                        .mapLeft { error ->
                            DeleteLocalMCPServerError.SecretCleanupFailed(
                                secretId = secretId,
                                message = "Failed to delete encrypted secret: $error",
                                cause = null
                            )
                        }
                        .bind()
                }
            }
        }

    override suspend fun getById(id: Long): Either<GetLocalMCPServerError, LocalMCPServer> =
        transactionScope.execute {
            either {
                val row = queries.getById(id).executeAsOneOrNull()
                ensure(row != null) { GetLocalMCPServerError.NotFound(id) }

                mapRowToModel(row).mapLeft { decryptionError ->
                    GetLocalMCPServerError.DecryptionFailed(
                        secretId = decryptionError.secretId,
                        message = decryptionError.message,
                        cause = decryptionError.cause
                    )
                }.bind()
            }
        }

    override suspend fun getAll(userId: Long): List<LocalMCPServer> =
        transactionScope.transaction {
            queries.getAllByUserId(userId)
                .executeAsList()
                .mapNotNull { row ->
                    mapRowToModel(row).onLeft { error ->
                        logger.warn("Failed to map LocalMCPServer row (id=${row.id}): $error")
                    }.getOrNull()
                }
        }

    override suspend fun getAllEnabled(userId: Long): List<LocalMCPServer> =
        transactionScope.transaction {
            queries.getAllEnabledByUserId(userId)
                .executeAsList()
                .mapNotNull { row ->
                    mapRowToModel(row).onLeft { error ->
                        logger.warn("Failed to map LocalMCPServer row (id=${row.id}): $error")
                    }.getOrNull()
                }
        }

    override suspend fun existsByName(
        name: String,
        userId: Long,
        excludeId: Long?
    ): Boolean =
        transactionScope.transaction {
            queries.existsByNameAndUserId(
                name = name,
                userId = userId,
                id = excludeId ?: -1L
            ).executeAsOne()
        }
}

