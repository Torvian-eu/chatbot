package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPServerError
import eu.torvian.chatbot.server.data.tables.LocalMCPServerTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*

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

