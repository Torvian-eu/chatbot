package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.*
import arrow.core.raise.*
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.server.data.dao.GroupDao
import eu.torvian.chatbot.server.data.dao.error.GroupError
import eu.torvian.chatbot.server.data.tables.ChatGroupTable
import eu.torvian.chatbot.server.data.tables.mappers.toChatGroup
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Exposed implementation of the [GroupDao].
 */
class GroupDaoExposed(
    private val transactionScope: TransactionScope
) : GroupDao {
    override suspend fun getAllGroups(): List<ChatGroup> =
        transactionScope.transaction {
            ChatGroupTable.selectAll().map { it.toChatGroup() }
        }

    override suspend fun getGroupById(id: Long): Either<GroupError.GroupNotFound, ChatGroup> =
        transactionScope.transaction {
            ChatGroupTable.selectAll().where { ChatGroupTable.id eq id }
                .singleOrNull()
                ?.toChatGroup()
                ?.right()
                ?: GroupError.GroupNotFound(id).left()
        }

    override suspend fun insertGroup(name: String): ChatGroup =
        transactionScope.transaction {
            val insertStatement = ChatGroupTable.insert {
                it[ChatGroupTable.name] = name
                it[ChatGroupTable.createdAt] = System.currentTimeMillis()
            }

            insertStatement.resultedValues?.first()?.toChatGroup()
                ?: throw IllegalStateException("Failed to retrieve newly inserted group after insertion.")
        }

    override suspend fun renameGroup(id: Long, newName: String): Either<GroupError, Unit> =
        transactionScope.transaction {
            either {
                val updatedRowCount = ChatGroupTable.update({ ChatGroupTable.id eq id }) {
                    it[name] = newName
                }
                ensure(updatedRowCount != 0) { GroupError.GroupNotFound(id) }
            }
        }

    override suspend fun deleteGroup(id: Long): Either<GroupError.GroupNotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = ChatGroupTable.deleteWhere { ChatGroupTable.id eq id }
                ensure(deletedCount != 0) { GroupError.GroupNotFound(id) }
            }
        }
}
