package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.server.data.dao.SessionOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.tables.ChatGroupTable
import eu.torvian.chatbot.server.data.tables.ChatSessionOwnersTable
import eu.torvian.chatbot.server.data.tables.ChatSessionTable
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.datetime.Instant
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*

/**
 * Exposed implementation of the [SessionOwnershipDao].
 */
class SessionOwnershipDaoExposed(
    private val transactionScope: TransactionScope
) : SessionOwnershipDao {

    override suspend fun getAllSessionsForUser(userId: Long): List<ChatSessionSummary> =
        transactionScope.transaction {
            ChatSessionTable
                .join(
                    ChatSessionOwnersTable,
                    JoinType.INNER,
                    additionalConstraint = { ChatSessionTable.id eq ChatSessionOwnersTable.sessionId }
                )
                .join(
                    ChatGroupTable,
                    JoinType.LEFT,
                    additionalConstraint = { ChatSessionTable.groupId eq ChatGroupTable.id }
                )
                .selectAll()
                .where { ChatSessionOwnersTable.userId eq userId }
                .orderBy(ChatSessionTable.updatedAt to SortOrder.DESC)
                .map { it.toChatSessionSummary() }
        }

    override suspend fun getOwner(sessionId: Long): Either<GetOwnerError, Long> =
        transactionScope.transaction {
            ChatSessionOwnersTable
                .selectAll()
                .where { ChatSessionOwnersTable.sessionId eq sessionId }
                .singleOrNull()
                ?.let { it[ChatSessionOwnersTable.userId].value }
                ?.right()
                ?: GetOwnerError.ResourceNotFound(sessionId.toString()).left()
        }

    override suspend fun setOwner(sessionId: Long, userId: Long): Either<SetOwnerError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    ChatSessionOwnersTable.insert {
                        it[ChatSessionOwnersTable.sessionId] = sessionId
                        it[ChatSessionOwnersTable.userId] = userId
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() -> 
                            raise(SetOwnerError.ForeignKeyViolation(sessionId.toString(), userId))
                        e.isUniqueConstraintViolation() -> 
                            raise(SetOwnerError.AlreadyOwned)
                        else -> throw e
                    }
                }
            }
        }

    /**
     * Maps an Exposed ResultRow from a join of ChatSessionTable, ChatSessionOwnersTable, and ChatGroupTable 
     * to a ChatSessionSummary DTO.
     */
    private fun ResultRow.toChatSessionSummary() = ChatSessionSummary(
        id = this[ChatSessionTable.id].value,
        name = this[ChatSessionTable.name],
        createdAt = Instant.fromEpochMilliseconds(this[ChatSessionTable.createdAt]),
        updatedAt = Instant.fromEpochMilliseconds(this[ChatSessionTable.updatedAt]),
        groupId = this[ChatSessionTable.groupId]?.value,
        groupName = this.getOrNull(ChatGroupTable.name)
    )
}
