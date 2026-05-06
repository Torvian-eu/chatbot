package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.UserSessionDao
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.data.tables.UserSessionsTable
import eu.torvian.chatbot.server.data.tables.mappers.toUserSessionEntity
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Exposed implementation of the [UserSessionDao].
 */
class UserSessionDaoExposed(
    private val transactionScope: TransactionScope
) : UserSessionDao {

    override suspend fun getSessionById(id: Long): Either<UserSessionError.SessionNotFound, UserSessionEntity> =
        transactionScope.transaction {
            UserSessionsTable.selectAll().where { UserSessionsTable.id eq id }
                .singleOrNull()
                ?.toUserSessionEntity()
                ?.right()
                ?: UserSessionError.SessionNotFound(id).left()
        }

    override suspend fun getSessionsByUserId(userId: Long): List<UserSessionEntity> =
        transactionScope.transaction {
            UserSessionsTable.selectAll().where { UserSessionsTable.userId eq userId }
                .map { it.toUserSessionEntity() }
        }

    override suspend fun insertSession(
        userId: Long,
        expiresAt: Long,
        ipAddress: String?,
        isRestricted: Boolean
    ): Either<UserSessionError.ForeignKeyViolation, UserSessionEntity> =
        transactionScope.transaction {
            either {
                catch({
                    val currentTime = System.currentTimeMillis()
                    val insertStatement = UserSessionsTable.insert {
                        it[UserSessionsTable.userId] = userId
                        it[UserSessionsTable.expiresAt] = expiresAt
                        it[createdAt] = currentTime
                        it[lastAccessed] = currentTime
                        it[UserSessionsTable.ipAddress] = ipAddress
                        it[UserSessionsTable.isRestricted] = isRestricted
                    }

                    insertStatement.resultedValues?.first()?.toUserSessionEntity()
                        ?: throw IllegalStateException("Failed to retrieve newly inserted user session after insertion.")
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() ->
                            raise(UserSessionError.ForeignKeyViolation("User with ID $userId does not exist"))

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun updateLastAccessed(
        id: Long,
        lastAccessed: Long
    ): Either<UserSessionError.SessionNotFound, Unit> =
        transactionScope.transaction {
            either {
                val updatedRowCount = UserSessionsTable.update({ UserSessionsTable.id eq id }) {
                    it[UserSessionsTable.lastAccessed] = lastAccessed
                }
                ensure(updatedRowCount != 0) { UserSessionError.SessionNotFound(id) }
            }
        }

    override suspend fun deleteSession(id: Long): Either<UserSessionError.SessionNotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = UserSessionsTable.deleteWhere { UserSessionsTable.id eq id }
                ensure(deletedCount != 0) { UserSessionError.SessionNotFound(id) }
            }
        }

    override suspend fun deleteSessionsByUserId(userId: Long): Int =
        transactionScope.transaction {
            UserSessionsTable.deleteWhere { UserSessionsTable.userId eq userId }
        }

    override suspend fun deleteExpiredSessions(currentTime: Long): Int =
        transactionScope.transaction {
            UserSessionsTable.deleteWhere { UserSessionsTable.expiresAt less currentTime }
        }
}
