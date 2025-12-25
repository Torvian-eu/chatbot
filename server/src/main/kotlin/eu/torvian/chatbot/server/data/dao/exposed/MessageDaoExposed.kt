package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.error.InsertMessageError
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.tables.AssistantMessageTable
import eu.torvian.chatbot.server.data.tables.ChatMessageTable
import eu.torvian.chatbot.server.data.tables.mappers.toAssistantMessage
import eu.torvian.chatbot.server.data.tables.mappers.toUserMessage
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
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

    override suspend fun insertMessage(
        sessionId: Long,
        targetMessageId: Long?,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String,
        modelId: Long?,
        settingsId: Long?
    ): Either<InsertMessageError, ChatMessage> =
        transactionScope.transaction {
            either {
                val now = System.currentTimeMillis()

                // 1.Determine Parent and Children for New Message
                val (newParentId, newChildrenIds) = if (targetMessageId == null) {
                    // Root message case
                    null to emptyList()
                } else {
                    // Relative insert case
                    val targetRow = ChatMessageTable
                        .selectAll()
                        .where { ChatMessageTable.id eq targetMessageId }
                        .singleOrNull()
                    ensure(targetRow != null) { InsertMessageError.ParentNotFound(targetMessageId) }

                    val targetSessionId = targetRow[ChatMessageTable.sessionId].value
                    ensure(targetSessionId == sessionId) {
                        InsertMessageError.ParentNotInSession(
                            targetMessageId,
                            sessionId
                        )
                    }

                    when (position) {
                        MessageInsertPosition.ABOVE -> {
                            val targetParentId = targetRow[ChatMessageTable.parentMessageId]?.value
                            targetParentId to listOf(targetMessageId)
                        }

                        MessageInsertPosition.BELOW -> {
                            val targetChildrenJson = targetRow[ChatMessageTable.childrenMessageIds]
                            val targetChildren = Json.decodeFromString<List<Long>>(targetChildrenJson)
                            targetMessageId to targetChildren
                        }

                        MessageInsertPosition.APPEND -> {
                            // Append as a new child (leaf) to the target
                            targetMessageId to emptyList()
                        }
                    }
                }

                // 2. Insert New Message with calculated parent and children
                val insertedRow = insertChatMessageRow(
                    sessionId = sessionId,
                    content = content,
                    parentMessageId = newParentId,
                    role = role,
                    now = now,
                    childrenMessageIds = newChildrenIds
                )
                val newMessageId = insertedRow[ChatMessageTable.id].value

                // Insert Assistant Metadata if needed
                if (role == ChatMessage.Role.ASSISTANT) {
                    AssistantMessageTable.insert {
                        it[AssistantMessageTable.messageId] = newMessageId
                        it[AssistantMessageTable.modelId] = modelId
                        it[AssistantMessageTable.settingsId] = settingsId
                    }
                }

                // 3. Handle Re-linking of neighbors
                if (targetMessageId != null) {
                    when (position) {
                        MessageInsertPosition.ABOVE -> {
                            // Update Target's Parent -> New Message
                            ChatMessageTable.update({ ChatMessageTable.id eq targetMessageId }) {
                                it[parentMessageId] = newMessageId
                            }

                            // Update Old Parent's Children (if exists) -> Replace Target with New Message
                            // newParentId is the old parent of the target
                            if (newParentId != null) {
                                val parentRow = ChatMessageTable
                                    .selectAll().where { ChatMessageTable.id eq newParentId }.singleOrNull()
                                    ?: throw IllegalStateException("Parent $newParentId not found")

                                val parentChildren =
                                    Json.decodeFromString<MutableList<Long>>(parentRow[ChatMessageTable.childrenMessageIds])
                                val idx = parentChildren.indexOf(targetMessageId)
                                if (idx != -1) {
                                    parentChildren[idx] = newMessageId
                                    ChatMessageTable.update({ ChatMessageTable.id eq newParentId }) {
                                        it[childrenMessageIds] = Json.encodeToString(parentChildren)
                                    }
                                }
                            }
                        }

                        MessageInsertPosition.BELOW -> {
                            // Update Target's Children -> [New Message]
                            ChatMessageTable.update({ ChatMessageTable.id eq targetMessageId }) {
                                it[childrenMessageIds] = Json.encodeToString(listOf(newMessageId))
                            }

                            // Update all old children's Parent -> New Message
                            // newChildrenIds contains the old children of the target
                            if (newChildrenIds.isNotEmpty()) {
                                ChatMessageTable.update({ ChatMessageTable.id inList newChildrenIds }) {
                                    it[parentMessageId] = newMessageId
                                }
                            }
                        }

                        MessageInsertPosition.APPEND -> {
                            // Append as a new child to the target
                            // We need to add the new message ID to the target's children list
                            val targetRow = ChatMessageTable
                                .selectAll().where { ChatMessageTable.id eq targetMessageId }.singleOrNull()
                                ?: throw IllegalStateException("Target $targetMessageId not found")

                            val targetChildren =
                                Json.decodeFromString<MutableList<Long>>(targetRow[ChatMessageTable.childrenMessageIds])
                            targetChildren.add(newMessageId)

                            ChatMessageTable.update({ ChatMessageTable.id eq targetMessageId }) {
                                it[childrenMessageIds] = Json.encodeToString(targetChildren)
                            }
                        }
                    }
                }

                // 4. Return the new message
                if (role == ChatMessage.Role.ASSISTANT) {
                    ChatMessage.AssistantMessage(
                        id = newMessageId,
                        sessionId = sessionId,
                        content = content,
                        createdAt = Instant.fromEpochMilliseconds(now),
                        updatedAt = Instant.fromEpochMilliseconds(now),
                        parentMessageId = newParentId,
                        childrenMessageIds = newChildrenIds,
                        modelId = modelId,
                        settingsId = settingsId
                    )
                } else {
                    ChatMessage.UserMessage(
                        id = newMessageId,
                        sessionId = sessionId,
                        content = content,
                        createdAt = Instant.fromEpochMilliseconds(now),
                        updatedAt = Instant.fromEpochMilliseconds(now),
                        parentMessageId = newParentId,
                        childrenMessageIds = newChildrenIds
                    )
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
        now: Long,
        childrenMessageIds: List<Long> = emptyList()
    ): ResultRow {
        val insertStatement = ChatMessageTable.insert {
            it[ChatMessageTable.sessionId] = sessionId
            it[ChatMessageTable.role] = role
            it[ChatMessageTable.content] = content
            it[ChatMessageTable.createdAt] = now
            it[ChatMessageTable.updatedAt] = now
            it[ChatMessageTable.parentMessageId] = parentMessageId
            it[ChatMessageTable.childrenMessageIds] = Json.encodeToString(childrenMessageIds)
        }
        return insertStatement.resultedValues?.first()
            ?: throw IllegalStateException("Failed to retrieve newly inserted message (role=$role)")
    }
}
