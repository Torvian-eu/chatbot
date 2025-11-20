package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.Raise
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.error.InsertMessageError
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.tables.AssistantMessageTable
import eu.torvian.chatbot.server.data.tables.ChatMessageTable
import eu.torvian.chatbot.server.data.tables.mappers.toAssistantMessage
import eu.torvian.chatbot.server.data.tables.mappers.toUserMessage
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
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
    ): Either<InsertMessageError, ChatMessage.UserMessage> =
        transactionScope.transaction {
            either {
                catch({
                    val now = System.currentTimeMillis()
                    val insertedRow = insertChatMessageRow(
                        sessionId = sessionId,
                        content = content,
                        parentMessageId = parentMessageId,
                        role = ChatMessage.Role.USER,
                        now = now
                    )

                    val insertedId = insertedRow[ChatMessageTable.id].value

                    // If there is a parent, atomically update parent's children list now
                    if (parentMessageId != null) {
                        linkChildToParent(parentMessageId, insertedId, sessionId)
                    }

                    // Return the inserted message
                    insertedRow.toUserMessage()
                }) { e: ExposedSQLException ->
                    logger.error(
                        "Failed to insert user message for session $sessionId and parent $parentMessageId", e
                    )
                    ensure(!e.isForeignKeyViolation()) { InsertMessageError.SessionNotFound(sessionId) }
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
    ): Either<InsertMessageError, ChatMessage.AssistantMessage> =
        transactionScope.transaction {
            either {
                catch({
                    val now = System.currentTimeMillis()
                    val messageRow = insertChatMessageRow(
                        sessionId = sessionId,
                        content = content,
                        parentMessageId = parentMessageId,
                        role = ChatMessage.Role.ASSISTANT,
                        now = now
                    )

                    val insertedId = messageRow[ChatMessageTable.id].value

                    // Insert into AssistantMessages table
                    AssistantMessageTable.insert {
                        it[AssistantMessageTable.messageId] = insertedId
                        it[AssistantMessageTable.modelId] = modelId
                        it[AssistantMessageTable.settingsId] = settingsId
                    }

                    // If there is a parent, atomically update parent's children list now
                    if (parentMessageId != null) {
                        linkChildToParent(parentMessageId, insertedId, sessionId)
                    }

                    // Return AssistantMessage object
                    ChatMessage.AssistantMessage(
                        id = insertedId,
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
                    logger.error(
                        "Failed to insert assistant message for session $sessionId and parent $parentMessageId", e
                    )
                    ensure(!e.isForeignKeyViolation()) { InsertMessageError.SessionNotFound(sessionId) }
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

    override suspend fun deleteMessageRecursively(id: Long): Either<MessageError.MessageNotFound, Unit> =
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

    override suspend fun deleteMessage(id: Long): Either<MessageError.MessageNotFound, Unit> =
        transactionScope.transaction {
            either {
                logger.debug("DAO: Deleting single message with ID $id (non-recursive)")

                // Load the message to delete
                val message = getMessageById(id).bind()
                val parentId = message.parentMessageId
                val sessionId = message.sessionId
                val children: List<Long> = message.childrenMessageIds

                // If there is a parent, update parent's children list by replacing this id with the children sequence
                if (parentId != null) {
                    val parentRow = ChatMessageTable
                        .selectAll().where { ChatMessageTable.id eq parentId }.singleOrNull()
                        ?: throw IllegalStateException("Parent message with ID $parentId not found when deleting child $id")

                    val parentSessionId = parentRow[ChatMessageTable.sessionId].value
                    if (parentSessionId != sessionId) {
                        throw IllegalStateException("Session mismatch between parent $parentId and child $id")
                    }

                    val parentChildrenJson = parentRow[ChatMessageTable.childrenMessageIds]
                    val parentChildren = Json.decodeFromString<MutableList<Long>>(parentChildrenJson)

                    val idx = parentChildren.indexOf(id)
                    if (idx == -1) {
                        throw IllegalStateException("Child ID $id not found in parent ID $parentId children list")
                    }
                    // Replace the deleted node with its children in place
                    parentChildren.removeAt(idx)
                    if (children.isNotEmpty()) {
                        parentChildren.addAll(idx, children)
                    }
                    val newParentChildrenJson = Json.encodeToString(parentChildren)
                    val updated = ChatMessageTable.update({ ChatMessageTable.id eq parentId }) {
                        it[childrenMessageIds] = newParentChildrenJson
                    }
                    if (updated == 0) {
                        throw IllegalStateException("Failed to update parent message with ID $parentId when deleting single $id")
                    }
                }

                // Reparent all direct children to the grandparent (or root)
                for (childId in children) {
                    val count = ChatMessageTable.update({ ChatMessageTable.id eq childId }) {
                        it[ChatMessageTable.parentMessageId] = parentId
                    }
                    if (count == 0) {
                        throw IllegalStateException("Failed to reparent child $childId when deleting single $id")
                    }
                }

                // Remove assistant metadata if any
                AssistantMessageTable.deleteWhere { AssistantMessageTable.messageId eq id }

                // Finally, delete only this message row
                val deleted = ChatMessageTable.deleteWhere { ChatMessageTable.id eq id }
                if (deleted == 0) {
                    throw IllegalStateException("Failed to delete message with ID $id")
                }

                logger.debug("DAO: Successfully deleted single message with ID $id")
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

    /**
     * Helper method to insert a new chat message row into the database.
     * Should only be called from within a transaction block.
     */
    private fun insertChatMessageRow(
        sessionId: Long,
        content: String,
        parentMessageId: Long?,
        role: ChatMessage.Role,
        now: Long
    ): ResultRow {
        val insertStatement = ChatMessageTable.insert {
            it[ChatMessageTable.sessionId] = sessionId
            it[ChatMessageTable.role] = role
            it[ChatMessageTable.content] = content
            it[ChatMessageTable.createdAt] = now
            it[ChatMessageTable.updatedAt] = now
            it[ChatMessageTable.parentMessageId] = parentMessageId
            it[ChatMessageTable.childrenMessageIds] = Json.encodeToString(emptyList<Long>())
        }
        return insertStatement.resultedValues?.first()
            ?: throw IllegalStateException("Failed to retrieve newly inserted message (role=$role)")
    }

    /**
     * Helper method to link a child message to its parent.
     * Should only be called from within a transaction block.
     *
     * @param parentMessageId The ID of the parent message
     * @param childId The ID of the child message
     * @param childSessionId The session ID of the child message to validate session consistency
     */
    private fun Raise<InsertMessageError>.linkChildToParent(
        parentMessageId: Long,
        childId: Long,
        childSessionId: Long
    ) {
        // Defensive check
        ensure(parentMessageId != childId) {
            InsertMessageError.ChildIsParent(parentMessageId, childId)
        }
        // Fetch parent row
        val parentRow = ChatMessageTable
            .selectAll()
            .where { ChatMessageTable.id eq parentMessageId }
            .singleOrNull()
        ensure(parentRow != null) { InsertMessageError.ParentNotFound(parentMessageId) }

        // Validate session match
        val parentSessionId = parentRow[ChatMessageTable.sessionId].value
        ensure(parentSessionId == childSessionId) {
            InsertMessageError.ParentNotInSession(parentMessageId, childSessionId)
        }

        // Update children list
        val currentChildrenIdsString = parentRow[ChatMessageTable.childrenMessageIds]
        val childrenList = Json.decodeFromString<MutableList<Long>>(currentChildrenIdsString)
        ensure(childId !in childrenList) { InsertMessageError.ChildAlreadyExists(parentMessageId, childId) }

        childrenList.add(childId)
        val newChildrenIdsString = Json.encodeToString(childrenList)
        val updated = ChatMessageTable.update({ ChatMessageTable.id eq parentMessageId }) {
            it[childrenMessageIds] = newChildrenIdsString
        }
        ensure(updated != 0) { InsertMessageError.ParentNotFound(parentMessageId) }
    }

}
