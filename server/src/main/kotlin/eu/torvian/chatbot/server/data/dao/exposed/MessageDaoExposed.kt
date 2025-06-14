package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.*
import arrow.core.raise.*
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.MessageAddChildError
import eu.torvian.chatbot.server.data.dao.error.MessageRemoveChildError
import eu.torvian.chatbot.server.data.tables.AssistantMessageTable
import eu.torvian.chatbot.server.data.tables.ChatMessageTable
import eu.torvian.chatbot.server.data.tables.mappers.toAssistantMessage
import eu.torvian.chatbot.server.data.tables.mappers.toUserMessage
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Exposed implementation of the [MessageDao].
 */
class MessageDaoExposed(
    private val transactionScope: TransactionScope
) : MessageDao {

    companion object {
        private val logger: Logger = LogManager.getLogger(MessageDaoExposed::class.java)
    }

    override suspend fun getMessagesBySessionId(sessionId: Long): List<ChatMessage> =
        transactionScope.transaction {
            // Perform a single query with left join to get all messages with their assistant data if any
            val results = ChatMessageTable
                .leftJoin(AssistantMessageTable, { ChatMessageTable.id }, { AssistantMessageTable.messageId })
                .selectAll()
                .where { ChatMessageTable.sessionId eq sessionId }
                .orderBy(ChatMessageTable.createdAt to SortOrder.ASC)

            // Transform to appropriate message types based on role
            results.map { row ->
                when (row[ChatMessageTable.role]) {
                    ChatMessage.Role.USER -> row.toUserMessage()
                    ChatMessage.Role.ASSISTANT -> row.toAssistantMessage()
                }
            }
        }

    override suspend fun getMessageById(id: Long): Either<MessageError.MessageNotFound, ChatMessage> =
        transactionScope.transaction {
            either {
                // Perform a single query with left join to get all needed data
                val query = ChatMessageTable
                    .leftJoin(AssistantMessageTable, { ChatMessageTable.id }, { AssistantMessageTable.messageId })
                    .selectAll()
                    .where { ChatMessageTable.id eq id }
                    .singleOrNull()
                ensure(query != null) { MessageError.MessageNotFound(id) }

                // Convert to appropriate message type based on role
                when (query[ChatMessageTable.role]) {
                    ChatMessage.Role.USER -> query.toUserMessage()
                    ChatMessage.Role.ASSISTANT -> query.toAssistantMessage()
                }
            }
        }

    override suspend fun insertUserMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long?
    ): Either<MessageError.ForeignKeyViolation, ChatMessage> =
        transactionScope.transaction {
            either {
                catch({
                    val now = System.currentTimeMillis()
                    val insertStatement = ChatMessageTable.insert {
                        it[ChatMessageTable.sessionId] = sessionId
                        it[ChatMessageTable.role] = ChatMessage.Role.USER
                        it[ChatMessageTable.content] = content
                        it[ChatMessageTable.createdAt] = now
                        it[ChatMessageTable.updatedAt] = now
                        it[ChatMessageTable.parentMessageId] = parentMessageId
                        it[ChatMessageTable.childrenMessageIds] = Json.encodeToString(emptyList<Long>())
                    }
                    insertStatement.resultedValues?.first()?.toUserMessage()
                        ?: throw IllegalStateException("Failed to retrieve newly inserted user message")
                }) { e: ExposedSQLException ->
                    val message = "Failed to insert user message for session $sessionId and parent $parentMessageId"
                    logger.error(message, e)
                    ensure(!e.isForeignKeyViolation()) { MessageError.ForeignKeyViolation(message) }
                    throw e
                }
            }
        }

    override suspend fun insertAssistantMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long?,
        modelId: Long?,
        settingsId: Long?
    ): Either<MessageError.ForeignKeyViolation, ChatMessage> =
        transactionScope.transaction {
            either {
                catch({
                    val now = System.currentTimeMillis()
                    val insertStatement = ChatMessageTable.insert {
                        it[ChatMessageTable.sessionId] = sessionId
                        it[ChatMessageTable.role] = ChatMessage.Role.ASSISTANT
                        it[ChatMessageTable.content] = content
                        it[ChatMessageTable.createdAt] = now
                        it[ChatMessageTable.updatedAt] = now
                        it[ChatMessageTable.parentMessageId] = parentMessageId
                        it[ChatMessageTable.childrenMessageIds] = Json.encodeToString(emptyList<Long>())
                    }
                    val messageRow = insertStatement.resultedValues?.first()
                        ?: throw IllegalStateException("Failed to retrieve newly inserted assistant message")

                    // Insert into AssistantMessages table
                    AssistantMessageTable.insert {
                        it[AssistantMessageTable.messageId] = messageRow[ChatMessageTable.id].value
                        it[AssistantMessageTable.modelId] = modelId
                        it[AssistantMessageTable.settingsId] = settingsId
                    }

                    // Return AssistantMessage object
                    ChatMessage.AssistantMessage(
                        id = messageRow[ChatMessageTable.id].value,
                        sessionId = messageRow[ChatMessageTable.sessionId].value,
                        content = messageRow[ChatMessageTable.content],
                        createdAt = Instant.fromEpochMilliseconds(messageRow[ChatMessageTable.createdAt]),
                        updatedAt = Instant.fromEpochMilliseconds(messageRow[ChatMessageTable.updatedAt]),
                        parentMessageId = messageRow[ChatMessageTable.parentMessageId]?.value,
                        childrenMessageIds = emptyList(),
                        modelId = modelId,
                        settingsId = settingsId
                    )
                }) { e: ExposedSQLException ->
                    val message =
                        "Failed to insert assistant message for session $sessionId and parent $parentMessageId"
                    logger.error(message, e)
                    ensure(!e.isForeignKeyViolation()) { MessageError.ForeignKeyViolation(message) }
                    throw e
                }
            }
        }

    override suspend fun updateMessageContent(
        id: Long,
        content: String
    ): Either<MessageError.MessageNotFound, ChatMessage> =
        transactionScope.transaction {
            either {
                val updatedRowCount = ChatMessageTable.update({ ChatMessageTable.id eq id }) {
                    it[ChatMessageTable.content] = content
                    it[ChatMessageTable.updatedAt] = System.currentTimeMillis()
                }
                ensure(updatedRowCount != 0) { MessageError.MessageNotFound(id) }

                // Retrieve the updated message
                getMessageById(id).getOrElse { throw IllegalStateException("Failed to retrieve updated message") }
            }
        }

    override suspend fun deleteMessage(id: Long): Either<MessageError.MessageNotFound, Unit> =
        transactionScope.transaction {
            either {
                logger.debug("DAO: Deleting message with ID $id")

                // Get the full message to access its children IDs
                val message = getMessageById(id).bind()

                // Recursively delete children
                for (childId in message.childrenMessageIds) {
                    deleteRecursively(childId)
                }

                // Delete the base message
                val deletedCount = ChatMessageTable.deleteWhere { ChatMessageTable.id eq id }
                if (deletedCount == 0)
                    throw IllegalStateException("Failed to delete message with ID $id")

                // If this message had a parent, update the parent's children list
                val parentId = message.parentMessageId
                if (parentId != null) {
                    val parentRow = ChatMessageTable
                        .selectAll().where { ChatMessageTable.id eq parentId }.singleOrNull()
                    if (parentRow == null) {
                        throw IllegalStateException("Parent message with ID $parentId not found when deleting child $id")
                    }
                    val currentChildrenIdsString = parentRow[ChatMessageTable.childrenMessageIds]
                    val childrenList = Json.decodeFromString<MutableList<Long>>(currentChildrenIdsString)

                    if (!childrenList.remove(id)) {
                        throw IllegalStateException("Child ID $id not found in parent ID $parentId children list")
                    }
                    val newChildrenIdsString = Json.encodeToString(childrenList)
                    val updatedRowCount = ChatMessageTable.update({ ChatMessageTable.id eq parentId }) {
                        it[ChatMessageTable.childrenMessageIds] = newChildrenIdsString
                    }
                    if (updatedRowCount == 0) {
                        throw IllegalStateException("Failed to update parent message with ID $parentId when deleting child $id")
                    }
                }
                logger.debug("DAO: Successfully deleted message with ID $id")
            }
        }

    override suspend fun addChildToMessage(parentId: Long, childId: Long): Either<MessageAddChildError, Unit> =
        transactionScope.transaction {
            either {
                logger.debug("DAO: Adding child message ID $childId to parent ID $parentId")
                // Get the parent message
                val parentMessage = getMessageById(parentId).mapLeft {
                    MessageAddChildError.ParentNotFound(parentId)
                }.bind()

                // Check if child already exists
                ensure(childId !in parentMessage.childrenMessageIds) {
                    MessageAddChildError.ChildAlreadyExists(parentId, childId)
                }

                // Update the parent's children list
                val updatedRowCount = ChatMessageTable.update({ ChatMessageTable.id eq parentId }) {
                    it[ChatMessageTable.childrenMessageIds] =
                        Json.encodeToString(parentMessage.childrenMessageIds + childId)
                }
                if (updatedRowCount == 0) {
                    throw IllegalStateException("Failed to update parent message with ID $parentId when adding child $childId")
                }
                logger.debug("DAO: Successfully added child ID $childId to parent ID $parentId")
            }
        }

    override suspend fun removeChildFromMessage(parentId: Long, childId: Long): Either<MessageRemoveChildError, Unit> =
        transactionScope.transaction {
            either {
                logger.debug("DAO: Removing child message ID $childId from parent ID $parentId")
                // Get the parent message
                val parentMessage = getMessageById(parentId).mapLeft {
                    MessageRemoveChildError.ParentNotFound(parentId)
                }.bind()

                // Check if child exists
                ensure(childId in parentMessage.childrenMessageIds) {
                    MessageRemoveChildError.ChildNotFound(parentId, childId)
                }

                // Update the parent's children list
                val updatedRowCount = ChatMessageTable.update({ ChatMessageTable.id eq parentId }) {
                    it[ChatMessageTable.childrenMessageIds] =
                        Json.encodeToString(parentMessage.childrenMessageIds - childId)
                }
                if (updatedRowCount == 0) {
                    throw IllegalStateException(
                        "Failed to update parent message with ID $parentId when removing child $childId"
                    )
                }
                logger.debug("DAO: Successfully removed child ID $childId from parent ID $parentId")
            }
        }

    /**
     * Helper method to recursively delete a message and all its children.
     * Should only be called from within a transaction block.
     */
    private suspend fun deleteRecursively(messageId: Long) {
        // Get the full message to access its children IDs
        val message = getMessageById(messageId).getOrElse {
            throw IllegalStateException("Failed to retrieve message with ID $messageId")
        }
        // First recursively delete all children
        for (childId in message.childrenMessageIds) {
            deleteRecursively(childId)
        }
        // Then delete this message
        val deletedCount = ChatMessageTable.deleteWhere { ChatMessageTable.id eq messageId }
        if (deletedCount == 0) {
            throw IllegalStateException("Failed to delete message with ID $messageId")
        }
    }
}
