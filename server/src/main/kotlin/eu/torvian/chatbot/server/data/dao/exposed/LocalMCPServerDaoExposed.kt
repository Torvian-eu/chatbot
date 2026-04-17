package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPServerError
import eu.torvian.chatbot.server.data.entities.CreateLocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.LocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.UpdateLocalMCPServerEntity
import eu.torvian.chatbot.server.data.tables.LocalMCPServerTable
import eu.torvian.chatbot.server.data.tables.mappers.toLocalMCPServerEntity
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Clock

/**
 * Exposed implementation of the [LocalMCPServerDao].
 *
 * This implementation uses the Exposed ORM framework to interact with the database.
 * It wraps all database operations in transactions provided by [TransactionScope]
 * and returns Either types for proper error handling.
 */
class LocalMCPServerDaoExposed(
    private val transactionScope: TransactionScope
) : LocalMCPServerDao {

    override suspend fun createServer(server: CreateLocalMCPServerEntity): LocalMCPServerEntity =
        transactionScope.transaction {
            val nowEpochMs = Clock.System.now().toEpochMilliseconds()
            val insertedId = LocalMCPServerTable.insertAndGetId {
                it[userId] = server.userId
                it[workerId] = server.workerId
                it[name] = server.name
                it[description] = server.description
                it[command] = server.command
                it[argumentsJson] = Json.encodeToString(server.arguments)
                it[workingDirectory] = server.workingDirectory
                it[isEnabled] = server.isEnabled
                it[autoStartOnEnable] = server.autoStartOnEnable
                it[autoStartOnLaunch] = server.autoStartOnLaunch
                it[autoStopAfterInactivitySeconds] = server.autoStopAfterInactivitySeconds
                it[toolNamePrefix] = server.toolNamePrefix
                it[environmentVariablesJson] = Json.encodeToString(server.environmentVariables)
                it[secretEnvironmentVariablesJson] = Json.encodeToString(server.secretEnvironmentVariables)
                it[createdAt] = nowEpochMs
                it[updatedAt] = nowEpochMs
            }.value

            LocalMCPServerTable
                .selectAll()
                .where { LocalMCPServerTable.id eq insertedId }
                .single()
                .toLocalMCPServerEntity()
        }

    override suspend fun updateServer(
        userId: Long,
        serverId: Long,
        server: UpdateLocalMCPServerEntity
    ): Either<LocalMCPServerError.Unauthorized, LocalMCPServerEntity> =
        transactionScope.transaction {
            either {
                val row = LocalMCPServerTable
                    .selectAll()
                    .where { LocalMCPServerTable.id eq serverId }
                    .singleOrNull()

                ensure(row?.get(LocalMCPServerTable.userId)?.value == userId) {
                    LocalMCPServerError.Unauthorized(userId, serverId)
                }

                LocalMCPServerTable.update(where = { LocalMCPServerTable.id eq serverId }) {
                    it[workerId] = server.workerId
                    it[name] = server.name
                    it[description] = server.description
                    it[command] = server.command
                    it[argumentsJson] = Json.encodeToString(server.arguments)
                    it[workingDirectory] = server.workingDirectory
                    it[isEnabled] = server.isEnabled
                    it[autoStartOnEnable] = server.autoStartOnEnable
                    it[autoStartOnLaunch] = server.autoStartOnLaunch
                    it[autoStopAfterInactivitySeconds] = server.autoStopAfterInactivitySeconds
                    it[toolNamePrefix] = server.toolNamePrefix
                    it[environmentVariablesJson] = Json.encodeToString(server.environmentVariables)
                    it[secretEnvironmentVariablesJson] = Json.encodeToString(server.secretEnvironmentVariables)
                    it[updatedAt] = Clock.System.now().toEpochMilliseconds()
                }

                LocalMCPServerTable
                    .selectAll()
                    .where { LocalMCPServerTable.id eq serverId }
                    .single()
                    .toLocalMCPServerEntity()
            }
        }

    override suspend fun getServerById(serverId: Long): Either<LocalMCPServerError.NotFound, LocalMCPServerEntity> =
        transactionScope.transaction {
            either {
                val row = LocalMCPServerTable
                    .selectAll()
                    .where { LocalMCPServerTable.id eq serverId }
                    .singleOrNull()

                ensure(row != null) { LocalMCPServerError.NotFound(serverId) }
                row.toLocalMCPServerEntity()
            }
        }

    override suspend fun getServerByIdForUser(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerError.Unauthorized, LocalMCPServerEntity> =
        transactionScope.transaction {
            either {
                val row = LocalMCPServerTable
                    .selectAll()
                    .where { LocalMCPServerTable.id eq serverId }
                    .singleOrNull()

                ensure(row?.get(LocalMCPServerTable.userId)?.value == userId) {
                    LocalMCPServerError.Unauthorized(userId, serverId)
                }
                row.toLocalMCPServerEntity()
            }
        }

    override suspend fun getServersByUserId(userId: Long): List<LocalMCPServerEntity> =
        transactionScope.transaction {
            LocalMCPServerTable
                .selectAll()
                .where { LocalMCPServerTable.userId eq userId }
                .map { it.toLocalMCPServerEntity() }
        }

    override suspend fun getServersByWorkerId(workerId: Long): List<LocalMCPServerEntity> =
        transactionScope.transaction {
            LocalMCPServerTable
                .selectAll()
                .where { LocalMCPServerTable.workerId eq workerId }
                .map { it.toLocalMCPServerEntity() }
        }

    @Deprecated("Use createServer(CreateLocalMCPServerEntity)")
    override suspend fun createServer(userId: Long, isEnabled: Boolean): Long =
        transactionScope.transaction {
            LocalMCPServerTable.insertAndGetId {
                it[LocalMCPServerTable.userId] = userId
                it[LocalMCPServerTable.isEnabled] = isEnabled
            }.value
        }

    override suspend fun deleteById(id: Long): Either<DeleteLocalMCPServerError, Unit> =
        transactionScope.transaction {
            either {
                val rowsDeleted = LocalMCPServerTable.deleteWhere {
                    LocalMCPServerTable.id eq id
                }
                ensure(rowsDeleted == 1) { DeleteLocalMCPServerError.NotFound(id) }
            }
        }

    @Deprecated("Use getServersByUserId")
    override suspend fun getIdsByUserId(userId: Long): List<Long> =
        transactionScope.transaction {
            LocalMCPServerTable
                .select(LocalMCPServerTable.id)
                .where { LocalMCPServerTable.userId eq userId }
                .map { it[LocalMCPServerTable.id].value }
        }

    override suspend fun existsById(id: Long): Boolean =
        transactionScope.transaction {
            LocalMCPServerTable
                .selectAll()
                .where { LocalMCPServerTable.id eq id }
                .count() > 0
        }

    override suspend fun validateOwnership(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerError.Unauthorized, Unit> =
        transactionScope.transaction {
            either {
                val ownerUserId = LocalMCPServerTable
                    .select(LocalMCPServerTable.userId)
                    .where { LocalMCPServerTable.id eq serverId }
                    .singleOrNull()
                    ?.get(LocalMCPServerTable.userId)

                ensure(ownerUserId?.value == userId) {
                    LocalMCPServerError.Unauthorized(userId, serverId)
                }
            }
        }

    @Deprecated("Use updateServer")
    override suspend fun setEnabled(serverId: Long, isEnabled: Boolean): Either<LocalMCPServerError.NotFound, Unit> =
        transactionScope.transaction {
            either {
                val rowsUpdated = LocalMCPServerTable.update(
                    where = { LocalMCPServerTable.id eq serverId }
                ) {
                    it[LocalMCPServerTable.isEnabled] = isEnabled
                }
                ensure(rowsUpdated == 1) { LocalMCPServerError.NotFound(serverId) }
            }
        }
}

