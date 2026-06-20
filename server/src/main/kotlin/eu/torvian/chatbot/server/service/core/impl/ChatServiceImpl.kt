package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.chat.preparation.ConversationTurnPreparationService
import eu.torvian.chatbot.server.service.core.chat.turn.ConversationTurnEvent
import eu.torvian.chatbot.server.service.core.chat.turn.ConversationTurnOrchestrator
import eu.torvian.chatbot.server.service.core.chat.turn.ConversationTurnRequest
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallApprovalSubmission
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Application service entry point for chat turns.
 *
 * Validation and runtime preparation are delegated to [conversationTurnPreparationService], while the
 * shared assistant/tool loop is delegated to [conversationTurnOrchestrator].
 *
 * @property conversationTurnOrchestrator Collaborator that runs the shared conversation-turn workflow.
 * @property conversationTurnPreparationService Collaborator that validates requests and resolves runtime inputs.
 */
class ChatServiceImpl(
    private val conversationTurnOrchestrator: ConversationTurnOrchestrator,
    private val conversationTurnPreparationService: ConversationTurnPreparationService,
) : ChatService {

    companion object {
        /** Logger used for unexpected application-service failures. */
        private val logger: Logger = LogManager.getLogger(ChatServiceImpl::class.java)
    }

    override suspend fun validateProcessNewMessageRequest(
        sessionId: Long,
        content: String?,
        parentMessageId: Long?,
        isStreaming: Boolean
    ): Either<ValidateNewMessageError, Pair<ChatSession, LLMConfig>> {
        return when (
            val preparedTurn = conversationTurnPreparationService
                .prepareNewMessageTurn(sessionId, content, parentMessageId, isStreaming)
        ) {
            is Either.Left -> preparedTurn.value.left()
            is Either.Right -> (preparedTurn.value.session to preparedTurn.value.llmConfig).right()
        }
    }

    override fun processNewMessage(
        userId: Long,
        session: ChatSession,
        llmConfig: LLMConfig,
        content: String?,
        parentMessageId: Long?,
        fileReferences: List<FileReference>,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>
    ): Flow<Either<ProcessNewMessageError, MessageEvent>> = channelFlow {
        try {
            conversationTurnOrchestrator.processNonStreamingTurn(
                ConversationTurnRequest(
                    userId = userId,
                    session = session,
                    llmConfig = llmConfig,
                    content = content,
                    parentMessageId = parentMessageId,
                    fileReferences = fileReferences,
                    toolApprovalFlow = toolApprovalFlow
                )
            ).collect { event ->
                send(event.toMessageEventEither())
            }
        } catch (e: Exception) {
            val errorMessage = "Unexpected error in processNewMessage for session ${session.id}: ${e.message}"
            logger.error(errorMessage, e)
            send(
                ProcessNewMessageError.UnexpectedError(errorMessage).left()
            )
            send(MessageEvent.StreamCompleted.right())
        }
    }

    override fun processNewMessageStreaming(
        userId: Long,
        session: ChatSession,
        llmConfig: LLMConfig,
        content: String?,
        parentMessageId: Long?,
        fileReferences: List<FileReference>,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>
    ): Flow<Either<ProcessNewMessageError, MessageStreamEvent>> = channelFlow {
        try {
            conversationTurnOrchestrator.processStreamingTurn(
                ConversationTurnRequest(
                    userId = userId,
                    session = session,
                    llmConfig = llmConfig,
                    content = content,
                    parentMessageId = parentMessageId,
                    fileReferences = fileReferences,
                    toolApprovalFlow = toolApprovalFlow
                )
            ).collect { event ->
                send(event.toMessageStreamEventEither())
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in processNewMessageStreaming for session ${session.id}: ${e.message}", e)
            send(
                ProcessNewMessageError.ExternalServiceError(
                    LLMCompletionError.InvalidResponseError("Unexpected error: ${e.message}")
                ).left()
            )
            send(MessageStreamEvent.StreamCompleted.right())
        }
    }

    /**
     * Maps internal turn events into the public non-streaming event surface.
     *
     * @return Public service event or processing error for the current turn event.
     */
    private fun ConversationTurnEvent.toMessageEventEither(): Either<ProcessNewMessageError, MessageEvent> {
        return when (this) {
            is ConversationTurnEvent.UserMessageSaved -> MessageEvent.UserMessageSaved(userMessage, updatedParentMessage).right()
            is ConversationTurnEvent.AssistantMessageSaved -> MessageEvent.AssistantMessageSaved(assistantMessage, updatedParentMessage).right()
            is ConversationTurnEvent.ToolCallsReceived -> MessageEvent.ToolCallsReceived(toolCalls).right()
            is ConversationTurnEvent.ToolCallApprovalRequested -> MessageEvent.ToolCallApprovalRequested(toolCall).right()
            is ConversationTurnEvent.ToolCallExecuting -> MessageEvent.ToolCallExecuting(toolCall).right()
            is ConversationTurnEvent.ToolExecutionCompleted -> MessageEvent.ToolExecutionCompleted(toolCall).right()
            is ConversationTurnEvent.ExternalServiceError -> ProcessNewMessageError.ExternalServiceError(llmError).left()
            ConversationTurnEvent.TurnCompleted -> MessageEvent.StreamCompleted.right()
            is ConversationTurnEvent.AssistantMessageStarted,
            is ConversationTurnEvent.AssistantMessageDelta,
            is ConversationTurnEvent.ToolCallDelta,
            is ConversationTurnEvent.AssistantMessageFinished -> {
                throw IllegalStateException("Streaming-only turn event emitted for non-streaming mapping: $this")
            }
        }
    }

    /**
     * Maps internal turn events into the public streaming event surface.
     *
     * @return Public streaming service event or processing error for the current turn event.
     */
    private fun ConversationTurnEvent.toMessageStreamEventEither(): Either<ProcessNewMessageError, MessageStreamEvent> {
        return when (this) {
            is ConversationTurnEvent.UserMessageSaved -> MessageStreamEvent.UserMessageSaved(userMessage, updatedParentMessage).right()
            is ConversationTurnEvent.AssistantMessageStarted -> {
                MessageStreamEvent.AssistantMessageStarted(assistantMessage, updatedParentMessage).right()
            }

            is ConversationTurnEvent.AssistantMessageDelta -> {
                MessageStreamEvent.AssistantMessageDelta(messageId, deltaContent).right()
            }

            is ConversationTurnEvent.ToolCallDelta -> {
                MessageStreamEvent.ToolCallDelta(messageId, index, id, name, argumentsDelta).right()
            }

            is ConversationTurnEvent.AssistantMessageFinished -> {
                MessageStreamEvent.AssistantMessageFinished(assistantMessage).right()
            }

            is ConversationTurnEvent.ToolCallsReceived -> MessageStreamEvent.ToolCallsReceived(toolCalls).right()
            is ConversationTurnEvent.ToolCallApprovalRequested -> MessageStreamEvent.ToolCallApprovalRequested(toolCall).right()
            is ConversationTurnEvent.ToolCallExecuting -> MessageStreamEvent.ToolCallExecuting(toolCall).right()
            is ConversationTurnEvent.ToolExecutionCompleted -> MessageStreamEvent.ToolExecutionCompleted(toolCall).right()
            is ConversationTurnEvent.ExternalServiceError -> ProcessNewMessageError.ExternalServiceError(llmError).left()
            ConversationTurnEvent.TurnCompleted -> MessageStreamEvent.StreamCompleted.right()
            is ConversationTurnEvent.AssistantMessageSaved -> {
                throw IllegalStateException("Non-streaming turn event emitted for streaming mapping: $this")
            }
        }
    }
}
