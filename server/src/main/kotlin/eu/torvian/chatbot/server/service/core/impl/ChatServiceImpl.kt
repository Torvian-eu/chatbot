package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModelCapabilities
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.hasCapability
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.chat.turn.ConversationTurnEvent
import eu.torvian.chatbot.server.service.core.chat.turn.ConversationTurnOrchestrator
import eu.torvian.chatbot.server.service.core.chat.turn.ConversationTurnRequest
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.core.error.model.GetModelError
import eu.torvian.chatbot.server.service.core.error.provider.GetProviderError
import eu.torvian.chatbot.server.service.core.error.settings.GetSettingsByIdError
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallApprovalSubmission
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.service.security.error.CredentialError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Service for processing new chat messages and managing the conversation flow.
 * Handles both streaming and non-streaming LLM interactions, including tool calling loops.
 *
 * @property messageDao DAO used by request validation to verify parent messages.
 * @property sessionDao DAO used by request validation to load sessions.
 * @property toolService Service used to resolve enabled tools for the validated session.
 * @property llmModelService Service used to resolve the selected model.
 * @property modelSettingsService Service used to resolve the selected settings profile.
 * @property llmProviderService Service used to resolve the selected provider.
 * @property credentialManager Service used to resolve provider credentials.
 * @property transactionScope Transaction wrapper used during validation.
 * @property conversationTurnOrchestrator Collaborator that runs the shared conversation-turn workflow.
 */
class ChatServiceImpl(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val toolService: ToolService,
    private val llmModelService: LLMModelService,
    private val modelSettingsService: ModelSettingsService,
    private val llmProviderService: LLMProviderService,
    private val credentialManager: CredentialManager,
    private val transactionScope: TransactionScope,
    private val conversationTurnOrchestrator: ConversationTurnOrchestrator,
) : ChatService {

    companion object {
        private val logger: Logger = LogManager.getLogger(ChatServiceImpl::class.java)
    }

    override suspend fun validateProcessNewMessageRequest(
        sessionId: Long,
        content: String?,
        parentMessageId: Long?,
        isStreaming: Boolean
    ): Either<ValidateNewMessageError, Pair<ChatSession, LLMConfig>> = transactionScope.transaction {
        either {
            // 0. Validate content and parentMessageId relationship (Branch & Continue mode)
            ensure(content != null || parentMessageId != null) {
                ValidateNewMessageError.ModelConfigurationError(
                    "Branch & Continue mode requires parentMessageId when content is null"
                )
            }

            // 1. Validate session
            val session = withError({ daoError: SessionError.SessionNotFound ->
                ValidateNewMessageError.SessionNotFound(daoError.id)
            }) {
                sessionDao.getSessionById(sessionId).bind()
            }

            // 2. Validate parent message if provided
            if (parentMessageId != null) {
                withError({ _: MessageError.MessageNotFound ->
                    ValidateNewMessageError.ParentNotInSession(sessionId, parentMessageId)
                }) {
                    messageDao.getMessageById(parentMessageId).bind()
                }
            }

            // 3. Get model and settings config
            val modelId = session.currentModelId
                ?: raise(ValidateNewMessageError.ModelConfigurationError("No model selected for session $sessionId"))
            val settingsId = session.currentSettingsId
                ?: raise(ValidateNewMessageError.ModelConfigurationError("No settings selected for session $sessionId"))

            // 4. Fetch Model
            val model = withError({ _: GetModelError ->
                throw IllegalStateException("Model with ID $modelId not found after validation")
            }) {
                llmModelService.getModelById(modelId).bind()
            }

            // 5. Fetch Settings
            val settings = withError({ _: GetSettingsByIdError ->
                throw IllegalStateException("Settings with ID $settingsId not found after validation")
            }) {
                modelSettingsService.getSettingsById(settingsId).bind()
            }

            // 6. Validate model and settings compatibility
            ensure(model.type == LLMModelType.CHAT) {
                ValidateNewMessageError.ModelConfigurationError(
                    "Model type ${model.type} is not supported for chat sessions"
                )
            }
            ensure(settings is ChatModelSettings) {
                ValidateNewMessageError.ModelConfigurationError(
                    "Settings type ${settings::class.simpleName} is not compatible with model type ${model.type}"
                )
            }

            ensure(settings.stream == isStreaming) {
                ValidateNewMessageError.ModelConfigurationError(
                    "Settings stream mode ${settings.stream} does not match requested stream mode $isStreaming"
                )
            }

            // 7. Get LLM provider
            val provider = withError({ _: GetProviderError ->
                throw IllegalStateException("Provider not found for model ID $modelId (provider ID: ${model.providerId})")
            }) {
                llmProviderService.getProviderById(model.providerId).bind()
            }

            // 8. Get API Key (if required)
            val apiKey = provider.apiKeyId?.let { keyId ->
                withError({ credentialError: CredentialError ->
                    when (credentialError) {
                        is CredentialError.CredentialNotFound ->
                            throw IllegalStateException("API key not found in secure storage for provider ID ${provider.id} (key alias: $keyId)")

                        is CredentialError.CredentialDecryptionFailed ->
                            throw IllegalStateException("API key could not be decrypted for provider ID ${provider.id} (key alias: $keyId)")
                    }
                }) {
                    credentialManager.getCredential(keyId).bind()
                }
            }

            // 9. Fetch and validate tools
            val tools = if (model.hasCapability(LLMModelCapabilities.TOOL_CALLING)) {
                // Model supports tool calling - fetch enabled tools for this session
                // Result can be an empty list if tool calling is supported but no tools are enabled
                toolService.getEnabledToolsForSession(sessionId)
            } else {
                // Model doesn't support tool calling - return null to indicate no tool calling capability
                null
            }

            // 9. Return session and llmConfig
            session to LLMConfig(provider, model, settings, apiKey, tools)
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
