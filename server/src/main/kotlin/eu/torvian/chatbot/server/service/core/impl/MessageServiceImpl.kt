package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.MessageService
import eu.torvian.chatbot.server.service.core.error.message.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import eu.torvian.chatbot.server.data.dao.error.InsertMessageError as DaoInsertMessageError

/**
 * Implementation of the [MessageService] interface.
 * Handles message persistence, threading, validation, and orchestration of chat processing.
 */
class MessageServiceImpl(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val transactionScope: TransactionScope,
) : MessageService {

    companion object {
        private val logger: Logger = LogManager.getLogger(MessageServiceImpl::class.java)
    }

    override suspend fun getMessagesBySessionId(sessionId: Long): List<ChatMessage> {
        return transactionScope.transaction {
            messageDao.getMessagesBySessionId(sessionId)
        }
    }

    override suspend fun getMessageById(id: Long): Either<GetMessageError, ChatMessage> =
        transactionScope.transaction {
            either {
                withError({ daoError: MessageError.MessageNotFound ->
                    GetMessageError.MessageNotFound(daoError.id)
                }) {
                    messageDao.getMessageById(id).bind()
                }
            }
        }

    override suspend fun updateMessageContent(
        id: Long,
        content: String
    ): Either<UpdateMessageContentError, ChatMessage> =
        transactionScope.transaction {
            either {
                withError({ daoError: MessageError.MessageNotFound ->
                    UpdateMessageContentError.MessageNotFound(daoError.id)
                }) {
                    messageDao.updateMessageContent(id, content).bind()
                }
            }
        }

    override suspend fun deleteMessageRecursively(id: Long): Either<DeleteMessageError, Unit> =
        transactionScope.transaction {
            either {
                // Get message details before deletion
                val messageToDelete = withError({ daoError: MessageError.MessageNotFound ->
                    DeleteMessageError.MessageNotFound(daoError.id)
                }) {
                    messageDao.getMessageById(id).bind()
                }

                val sessionId = messageToDelete.sessionId

                // Get session and check if update is needed BEFORE deletion
                val session = withError({ _: SessionError ->
                    DeleteMessageError.SessionUpdateFailed(sessionId)
                }) {
                    sessionDao.getSessionById(sessionId).bind()
                }

                val currentLeafId = session.currentLeafMessageId

                // Check if the deleted message affects current leaf and calculate new leaf ID if needed
                val leafUpdateResult = calculateLeafUpdateIfNeeded(
                    messageToDelete, session, currentLeafId
                )

                // Perform the deletion
                withError({ daoError: MessageError.MessageNotFound ->
                    DeleteMessageError.MessageNotFound(daoError.id)
                }) {
                    messageDao.deleteMessageRecursively(id).bind()
                }

                // Update session leaf message if needed
                if (leafUpdateResult.needsUpdate) {
                    withError({ _: SessionError ->
                        DeleteMessageError.SessionUpdateFailed(sessionId)
                    }) {
                        sessionDao.updateSessionLeafMessageId(sessionId, leafUpdateResult.newLeafId).bind()
                    }
                }
            }
        }

    override suspend fun deleteMessage(id: Long): Either<DeleteMessageError, Unit> =
        transactionScope.transaction {
            either {
                // Get message details before deletion
                val messageToDelete = withError({ daoError: MessageError.MessageNotFound ->
                    DeleteMessageError.MessageNotFound(daoError.id)
                }) {
                    messageDao.getMessageById(id).bind()
                }

                val sessionId = messageToDelete.sessionId

                // Get session BEFORE deletion
                val session = withError({ _: SessionError ->
                    DeleteMessageError.SessionUpdateFailed(sessionId)
                }) {
                    sessionDao.getSessionById(sessionId).bind()
                }

                val currentLeafId = session.currentLeafMessageId

                // Calculate new leaf for single-delete semantics
                val leafUpdateResult = calculateLeafUpdateForSingleDelete(
                    messageToDelete, session, currentLeafId
                )

                // Perform the single deletion
                withError({ daoError: MessageError.MessageNotFound ->
                    DeleteMessageError.MessageNotFound(daoError.id)
                }) {
                    messageDao.deleteMessage(id).bind()
                }

                // Update session leaf message if needed
                if (leafUpdateResult.needsUpdate) {
                    withError({ _: SessionError ->
                        DeleteMessageError.SessionUpdateFailed(sessionId)
                    }) {
                        sessionDao.updateSessionLeafMessageId(sessionId, leafUpdateResult.newLeafId).bind()
                    }
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
                // Insert the new message
                val newMessage = withError({ daoError: DaoInsertMessageError ->
                    daoError.toServiceError()
                }) {
                     messageDao.insertMessage(
                        sessionId = sessionId,
                        targetMessageId = targetMessageId,
                        position = position,
                        role = role,
                        content = content,
                        modelId = modelId,
                        settingsId = settingsId
                    ).bind()
                }

                // If the new message is a leaf (has no children), update the session's current leaf to it.
                if (newMessage.childrenMessageIds.isEmpty()) {
                    withError({ sessionError: SessionError ->
                        sessionError.toServiceError()
                    }) {
                        sessionDao.updateSessionLeafMessageId(sessionId, newMessage.id).bind()
                    }
                }

                newMessage
            }
        }

    /**
     * Checks if a target message is in the path from root to a leaf message.
     *
     * @param targetMessageId The ID of the message to check for.
     * @param leafMessageId The ID of the leaf message to trace back from.
     * @param messageMap Map of message ID to ChatMessage for efficient lookups.
     * @return True if the target message is an ancestor of the leaf message.
     */
    private fun isMessageInPath(
        targetMessageId: Long,
        leafMessageId: Long,
        messageMap: Map<Long, ChatMessage>
    ): Boolean {
        var currentId: Long? = leafMessageId
        while (currentId != null) {
            if (currentId == targetMessageId) return true
            currentId = messageMap[currentId]?.parentMessageId
        }
        return false
    }

    /**
     * Finds the leaf message in a subtree by following the first child path.
     *
     * @param rootMessageId The ID of the root message to start from.
     * @param messageMap Map of message ID to ChatMessage for efficient lookups.
     * @return The ID of the leaf message in this subtree.
     */
    private fun findLeafInSubtree(rootMessageId: Long, messageMap: Map<Long, ChatMessage>): Long {
        var currentId = rootMessageId
        while (true) {
            val message = messageMap[currentId]
                ?: throw IllegalStateException("Message $currentId not found in message map")
            if (message.childrenMessageIds.isEmpty()) {
                return currentId
            }
            // Follow first child path
            currentId = message.childrenMessageIds.first()
        }
    }

    /**
     * Finds the first available root message in a session, excluding the deleted one.
     *
     * @param sessionId The ID of the session.
     * @param deletedMessageId The ID of the message being deleted (to exclude).
     * @param messageMap Map of message ID to ChatMessage for efficient lookups.
     * @return The ID of the oldest available root message, or null if none exist.
     */
    private fun findFirstAvailableRootMessage(
        sessionId: Long,
        deletedMessageId: Long,
        messageMap: Map<Long, ChatMessage>
    ): Long? {
        // Find all root messages (parentMessageId == null) excluding the deleted one
        return messageMap.values
            .filter { it.sessionId == sessionId && it.parentMessageId == null && it.id != deletedMessageId }
            .minByOrNull { it.createdAt }  // Use oldest root as the new active branch
            ?.id
    }

    /**
     * Calculates if the leaf message ID needs to be updated after a deletion,
     * and determines the new leaf message ID if needed.
     *
     * @param messageToDelete The message that is being deleted.
     * @param session The current session.
     * @param currentLeafId The current leaf message ID of the session.
     * @return A [LeafUpdateCalculation] indicating if an update is needed and the new leaf ID.
     */
    private suspend fun calculateLeafUpdateIfNeeded(
        messageToDelete: ChatMessage,
        session: ChatSession,
        currentLeafId: Long?
    ): LeafUpdateCalculation {
        // If there's no current leaf, no update is needed
        if (currentLeafId == null) {
            return LeafUpdateCalculation(needsUpdate = false, newLeafId = null)
        }

        // Build fresh message map from the database
        val allMessages = messageDao.getMessagesBySessionId(session.id)
        val messageMap = allMessages.associateBy { it.id }

        // Check if the deleted message affects current leaf
        val needsLeafUpdate = isMessageInPath(messageToDelete.id, currentLeafId, messageMap)

        if (!needsLeafUpdate) {
            return LeafUpdateCalculation(needsUpdate = false, newLeafId = null)
        }

        // Calculate new leaf message before deletion
        val newLeafId = when (val parentId = messageToDelete.parentMessageId) {
            null -> {
                // Deleted a root message, find another available root
                val nextRootId = findFirstAvailableRootMessage(session.id, messageToDelete.id, messageMap)
                if (nextRootId != null) {
                    // Traverse down to find the leaf of this root
                    findLeafInSubtree(nextRootId, messageMap)
                } else {
                    // No more root messages, session becomes empty
                    null
                }
            }

            else -> {
                // Get parent's current state (before deletion)
                val parent = messageMap[parentId]
                    ?: throw IllegalStateException("Parent message $parentId not found")

                // Calculate remaining children after deletion
                val remainingChildren = parent.childrenMessageIds.filter { it != messageToDelete.id }

                if (remainingChildren.isEmpty()) {
                    // Parent becomes the new leaf
                    parentId
                } else {
                    // Find leaf in first remaining child's subtree
                    findLeafInSubtree(remainingChildren.first(), messageMap)
                }
            }
        }

        return LeafUpdateCalculation(needsUpdate = true, newLeafId = newLeafId)
    }

    /**
     * Calculates leaf update for single-message delete where children are promoted.
     */
    private suspend fun calculateLeafUpdateForSingleDelete(
        messageToDelete: ChatMessage,
        session: ChatSession,
        currentLeafId: Long?
    ): LeafUpdateCalculation {
        if (currentLeafId == null) return LeafUpdateCalculation(needsUpdate = false, newLeafId = null)

        val allMessages = messageDao.getMessagesBySessionId(session.id)
        val messageMap = allMessages.associateBy { it.id }

        val affectsLeaf = isMessageInPath(messageToDelete.id, currentLeafId, messageMap)
        if (!affectsLeaf) return LeafUpdateCalculation(needsUpdate = false, newLeafId = null)

        val parentId = messageToDelete.parentMessageId

        // Calculate new leaf message before deletion
        val newLeafId = if (currentLeafId == messageToDelete.id) {
            if (parentId == null) {
                // Deleted root message, find another available root
                val nextRootId = findFirstAvailableRootMessage(session.id, messageToDelete.id, messageMap)
                nextRootId?.let { findLeafInSubtree(it, messageMap) }
            } else {
                val parent = messageMap[parentId] ?: throw IllegalStateException("Parent message $parentId not found")
                val otherChildOfParent = parent.childrenMessageIds.firstOrNull { it != messageToDelete.id }
                if (otherChildOfParent == null) {
                    // Parent becomes the new leaf
                    parentId
                } else {
                    // Find leaf in first remaining child's subtree
                    findLeafInSubtree(otherChildOfParent, messageMap)
                }
            }
        } else {
            // Leaf is deeper in the deleted node's subtree; its ID remains the same after promotion
            currentLeafId
        }

        return LeafUpdateCalculation(needsUpdate = true, newLeafId = newLeafId)
    }
}

/**
 * Result of leaf update calculation.
 */
private data class LeafUpdateCalculation(
    val needsUpdate: Boolean,
    val newLeafId: Long?
)
