package eu.torvian.chatbot.server.service.core.chat.persistence

import arrow.core.getOrElse
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import kotlin.time.Clock

/**
 * Default [ConversationTurnPersistence] that preserves the existing message and tool-call persistence workflow.
 *
 * @property messageDao DAO used to persist and refresh chat messages.
 * @property sessionDao DAO used to advance the session leaf pointer.
 * @property toolCallDao DAO used to persist and load tool-call records.
 * @property transactionScope Transaction wrapper used for atomic message persistence steps.
 */
class DefaultConversationTurnPersistence(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val toolCallDao: ToolCallDao,
    private val transactionScope: TransactionScope,
) : ConversationTurnPersistence {
    override suspend fun saveUserMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long?,
        fileReferences: List<FileReference>
    ): PersistedUserMessage = transactionScope.transaction {
        val userMessage = messageDao.insertMessage(
            sessionId = sessionId,
            targetMessageId = parentMessageId,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.USER,
            content = content,
            modelId = null,
            settingsId = null,
            fileReferences = fileReferences
        ).getOrElse { daoError ->
            throw IllegalStateException(
                "Failed to insert user message. Session id: $sessionId. " +
                    "Parent message id: $parentMessageId. Error: $daoError"
            )
        } as ChatMessage.UserMessage

        sessionDao.updateSessionLeafMessageId(sessionId, userMessage.id).getOrElse { updateError ->
            throw IllegalStateException(
                "Failed to update session leaf message ID. Session id: $sessionId. " +
                    "New leaf message id: ${userMessage.id}. Error: $updateError"
            )
        }

        val updatedParentMessage = parentMessageId?.let { id ->
            // Reload the parent so callers see the refreshed child linkage created by insertMessage().
            messageDao.getMessageById(id).getOrElse { daoError ->
                throw IllegalStateException(
                    "Failed to retrieve updated parent message. Parent message id: $id. Error: $daoError"
                )
            }
        }

        PersistedUserMessage(userMessage = userMessage, updatedParentMessage = updatedParentMessage)
    }

    override suspend fun saveAssistantMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long,
        model: LLMModel,
        settings: ChatModelSettings
    ): PersistedAssistantMessage = transactionScope.transaction {
        val assistantMessage = messageDao.insertMessage(
            sessionId = sessionId,
            targetMessageId = parentMessageId,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.ASSISTANT,
            content = content,
            modelId = model.id,
            settingsId = settings.id
        ).getOrElse { daoError ->
            throw IllegalStateException(
                "Failed to insert assistant message. Session id: $sessionId. " +
                    "Parent message id: $parentMessageId. Error: $daoError"
            )
        } as ChatMessage.AssistantMessage

        sessionDao.updateSessionLeafMessageId(sessionId, assistantMessage.id).getOrElse { updateError ->
            throw IllegalStateException(
                "Failed to update session leaf message ID. Session id: $sessionId. " +
                    "New leaf message id: ${assistantMessage.id}. Error: $updateError"
            )
        }

        val updatedParentMessage = messageDao.getMessageById(parentMessageId).getOrElse { daoError ->
            throw IllegalStateException(
                "Failed to retrieve updated parent message. Parent message id: $parentMessageId. Error: $daoError"
            )
        }

        PersistedAssistantMessage(
            assistantMessage = assistantMessage,
            updatedParentMessage = updatedParentMessage
        )
    }

    override suspend fun updateAssistantMessageContent(
        messageId: Long,
        content: String
    ): ChatMessage.AssistantMessage = transactionScope.transaction {
        messageDao.updateMessageContent(messageId, content).getOrElse { error ->
            throw IllegalStateException("Failed to update assistant message content: $error")
        } as ChatMessage.AssistantMessage
    }

    override suspend fun persistPendingToolCalls(
        messageId: Long,
        toolCallRequests: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>,
        enabledTools: List<ToolDefinition>?
    ): List<ToolCall> {
        return toolCallRequests.map { toolCallRequest ->
            val toolDefinition = enabledTools?.find { it.name == toolCallRequest.name }
            toolCallDao.insertToolCall(
                messageId = messageId,
                toolDefinitionId = toolDefinition?.id,
                toolName = toolCallRequest.name,
                toolCallId = toolCallRequest.toolCallId,
                input = toolCallRequest.arguments,
                output = null,
                status = if (toolDefinition == null) ToolCallStatus.ERROR else ToolCallStatus.PENDING,
                errorMessage = if (toolDefinition == null) {
                    "Tool '${toolCallRequest.name}' not found in enabled tools"
                } else {
                    null
                },
                denialReason = null,
                executedAt = Clock.System.now(),
                durationMs = null
            ).getOrElse { error ->
                throw IllegalStateException("Failed to insert tool call: $error")
            }
        }
    }

    override suspend fun loadSessionToolCalls(sessionId: Long): List<ToolCall> {
        return toolCallDao.getToolCallsBySessionId(sessionId).sortedBy { it.id }
    }
}