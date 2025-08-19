package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import arrow.core.right
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.error.message.DeleteMessageError
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.UpdateMessageContentError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.core.error.model.GetModelError
import eu.torvian.chatbot.server.service.core.error.provider.GetProviderError
import eu.torvian.chatbot.server.service.core.error.settings.GetSettingsByIdError
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.service.security.error.CredentialError
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.datetime.Clock
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of the [MessageService] interface.
 * Orchestrates message persistence, threading, and LLM interaction.
 */
class MessageServiceImpl(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val llmModelService: LLMModelService,
    private val modelSettingsService: ModelSettingsService,
    private val llmProviderService: LLMProviderService,
    private val llmApiClient: LLMApiClient,
    private val credentialManager: CredentialManager,
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

    override suspend fun validateProcessNewMessageRequest(
        sessionId: Long,
        parentMessageId: Long?
    ): Either<ValidateNewMessageError, Pair<ChatSession, LLMConfig>> = transactionScope.transaction {
        either {
            // 1. Validate session
            val session = withError({ daoError: SessionError.SessionNotFound ->
                ValidateNewMessageError.SessionNotFound(daoError.id)
            }) {
                sessionDao.getSessionById(sessionId).bind()
            }

            // 2. Validate parent message if provided
            if (parentMessageId != null) {
                withError({ daoError: MessageError.MessageNotFound ->
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
            val model = withError({ serviceError: GetModelError ->
                throw IllegalStateException("Model with ID $modelId not found after validation")
            }) {
                llmModelService.getModelById(modelId).bind()
            }

            // 5. Fetch Settings
            val settings = withError({ serviceError: GetSettingsByIdError ->
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

            // 7. Get LLM provider
            val provider = withError({ serviceError: GetProviderError ->
                throw IllegalStateException("Provider not found for model ID $modelId (provider ID: ${model.providerId})")
            }) {
                llmProviderService.getProviderById(model.providerId).bind()
            }

            // 8. Get API Key (if required)
            val apiKey = provider.apiKeyId?.let { keyId ->
                withError({ credError: CredentialError.CredentialNotFound ->
                    throw IllegalStateException("API key not found in secure storage for provider ID ${provider.id} (key alias: $keyId)")
                }) {
                    credentialManager.getCredential(keyId).bind()
                }
            }

            // 9. Return session and llmConfig
            session to LLMConfig(provider, model, settings, apiKey)
        }
    }

    override suspend fun processNewMessage(
        session: ChatSession,
        llmConfig: LLMConfig,
        content: String,
        parentMessageId: Long?
    ): Either<ProcessNewMessageError, List<ChatMessage>> =
        either {
            // 1. Save user message
            val userMessage = saveUserMessage(session.id, content, parentMessageId)

            // 2. Build context
            val context = buildContext(userMessage, session.messages)

            // 3. Call LLM API
            val llmResponseResult = withError({ llmError: LLMCompletionError ->
                logger.error("LLM API call failed for session ${session.id}, provider ${llmConfig.provider.name}: $llmError")
                ProcessNewMessageError.ExternalServiceError(llmError)
            }) {
                llmApiClient.completeChat(
                    messages = context,
                    modelConfig = llmConfig.model,
                    provider = llmConfig.provider,
                    settings = llmConfig.settings as ChatModelSettings,
                    apiKey = llmConfig.apiKey
                ).bind()
            }
            logger.info("LLM API call successful for session ${session.id}")

            // 4. Process the LLM response.
            val assistantMessageContent =
                llmResponseResult.choices.firstOrNull()?.content ?: run {
                    logger.error("LLM API returned successful response with no choices or empty content for session ${session.id}")
                    // Treat an empty response as an error scenario
                    raise(ProcessNewMessageError.ExternalServiceError(LLMCompletionError.InvalidResponseError("LLM API returned success but no completion choices.")))
                }

            // 5. Save assistant message
            val (assistantMessage, updatedUserMessage) = saveAssistantMessage(
                session.id, assistantMessageContent, userMessage, llmConfig.model, llmConfig.settings
            )

            // 6. Return new messages as the success value
            listOf(updatedUserMessage, assistantMessage)
        }

    override fun processNewMessageStreaming(
        session: ChatSession,
        llmConfig: LLMConfig,
        content: String,
        parentMessageId: Long?
    ): Flow<Either<ProcessNewMessageError, MessageStreamEvent>> = channelFlow {

        // Step 1: Save user message
        val userMessage = saveUserMessage(session.id, content, parentMessageId)
        send(MessageStreamEvent.UserMessageSaved(userMessage).right())

        // Step 2: Build context and create temporary assistant message
        val context = buildContext(userMessage, session.messages)
        val temporaryAssistantMessage = createTemporaryAssistantMessage(
            session.id, userMessage, llmConfig.model, llmConfig.settings
        )
        send(MessageStreamEvent.AssistantMessageStarted(temporaryAssistantMessage).right())

        // Step 3: Handle streaming with callbacks
        handleLlmStreaming(
            context = context,
            model = llmConfig.model,
            provider = llmConfig.provider,
            settings = llmConfig.settings,
            apiKey = llmConfig.apiKey,
            onContentDelta = { deltaContent ->
                // Emit content delta
                send(MessageStreamEvent.AssistantMessageDelta(temporaryAssistantMessage.id, deltaContent).right())
            },
            onStreamComplete = { finalContent ->
                // Save assistant message
                val (savedAssistantMessage, updatedUserMessage) = saveAssistantMessage(
                    session.id, finalContent, userMessage, llmConfig.model, llmConfig.settings
                )

                // Emit final assistant message and stream completion
                send(
                    MessageStreamEvent.AssistantMessageCompleted(
                        tempMessageId = temporaryAssistantMessage.id,
                        finalAssistantMessage = savedAssistantMessage,
                        finalUserMessage = updatedUserMessage
                    ).right()
                )
                send(MessageStreamEvent.StreamCompleted.right())
            },
            onError = { llmError ->
                // Emit error and signal termination
                logger.error("LLM API streaming error for session ${session.id}, provider ${llmConfig.provider.name}: $llmError")
                send(ProcessNewMessageError.ExternalServiceError(llmError).left())
                send(MessageStreamEvent.StreamCompleted.right())
            }
        )
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

    override suspend fun deleteMessage(id: Long): Either<DeleteMessageError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: MessageError.MessageNotFound ->
                    DeleteMessageError.MessageNotFound(daoError.id)
                }) {
                    messageDao.deleteMessage(id).bind()
                }
            }
        }

    /**
     * Saves user message and updates relationships.
     */
    private suspend fun saveUserMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long?
    ): ChatMessage.UserMessage = transactionScope.transaction {
        // 1. Insert user message
        val userMessage = messageDao.insertUserMessage(sessionId, content, parentMessageId).getOrElse { daoError ->
            throw IllegalStateException(
                "Failed to insert user message. " +
                        "Session id: $sessionId. Parent message id: $parentMessageId. Error: $daoError"
            )
        }

        // 2. Link user message as child to parent if parentMessageId is provided
        // TODO: update MessageDao.insertUserMessage to handle this
        if (parentMessageId != null) {
            messageDao.addChildToMessage(parentMessageId, userMessage.id).getOrElse { linkError ->
                throw IllegalStateException(
                    "Failed to link user message as child to parent. " +
                            "Parent message id: $parentMessageId. User message id: ${userMessage.id}. " +
                            "Error: $linkError"
                )
            }
        }

        // 3. Update session's leaf message ID
        sessionDao.updateSessionLeafMessageId(sessionId, userMessage.id).getOrElse { updateError ->
            throw IllegalStateException(
                "Failed to update session leaf message ID. " +
                        "Session id: $sessionId. New leaf message id: ${userMessage.id}. Error: $updateError"
            )
        }

        // 4. Return user message
        userMessage
    }

    /**
     * Builds the context for the LLM API call with the new user message as leaf.
     *
     * @param currentUserMessage The user's message.
     * @param allMessages All messages in the session.
     * @return The context as a list of [ChatMessage] objects.
     */
    private fun buildContext(currentUserMessage: ChatMessage, allMessages: List<ChatMessage>): List<ChatMessage> {
        val context = mutableListOf<ChatMessage>()
        val messageMap = allMessages.associateBy { it.id }
        var c: ChatMessage? = currentUserMessage
        while (c != null) {
            context.add(c)
            c = c.parentMessageId?.let { messageMap[it] }
        }
        return context.reversed()
    }

    /**
     * Saves assistant message and updates relationships.
     */
    private suspend fun saveAssistantMessage(
        sessionId: Long,
        content: String,
        userMessage: ChatMessage.UserMessage,
        model: LLMModel,
        settings: ModelSettings
    ): Pair<ChatMessage.AssistantMessage, ChatMessage.UserMessage> =
        transactionScope.transaction {
            // 1. Insert assistant message
            val assistantMsg = messageDao.insertAssistantMessage(
                sessionId, content, userMessage.id, model.id, settings.id
            ).getOrElse { daoError ->
                throw IllegalStateException(
                    "Failed to insert final assistant message. " +
                            "Session id: $sessionId. Parent message id: ${userMessage.id}. Error: ${daoError::class.simpleName}"
                )
            }

            // 2. Link assistant message as child to user message
            // TODO: update MessageDao.insertAssistantMessage to handle this
            messageDao.addChildToMessage(userMessage.id, assistantMsg.id).mapLeft { linkError ->
                throw IllegalStateException(
                    "Failed to link assistant message as child to user message. " +
                            "User message id: ${userMessage.id}. Assistant message id: ${assistantMsg.id}. " +
                            "Error: $linkError"
                )
            }

            // 3. Update session's leaf message ID
            sessionDao.updateSessionLeafMessageId(sessionId, assistantMsg.id).mapLeft { updateError ->
                throw IllegalStateException(
                    "Failed to update session leaf message ID. " +
                            "Session id: $sessionId. New leaf message id: ${assistantMsg.id}. Error: $updateError"
                )
            }

            // 4. Retrieve updated user message
            val updatedUserMsg = messageDao.getMessageById(userMessage.id).getOrElse { daoError ->
                throw IllegalStateException(
                    "Failed to retrieve updated user message. " +
                            "User message id: ${userMessage.id}. Error: $daoError"
                )
            } as ChatMessage.UserMessage

            assistantMsg to updatedUserMsg
        }

    /**
     * Creates temporary assistant message for streaming.
     */
    private fun createTemporaryAssistantMessage(
        sessionId: Long,
        userMessage: ChatMessage.UserMessage,
        model: LLMModel,
        settings: ModelSettings
    ): ChatMessage.AssistantMessage {
        return ChatMessage.AssistantMessage(
            id = -1L, // Temporary ID
            sessionId = sessionId,
            content = "",
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
            modelId = model.id,
            settingsId = settings.id
        )
    }

    /**
     * Handles LLM streaming response and emits updates.
     */
    private suspend fun handleLlmStreaming(
        context: List<ChatMessage>,
        model: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?,
        onContentDelta: suspend (String) -> Unit,
        onStreamComplete: suspend (String) -> Unit,
        onError: suspend (LLMCompletionError) -> Unit
    ) {
        var accumulatedContent = ""

        llmApiClient.completeChatStreaming(context, model, provider, settings as ChatModelSettings, apiKey)
            .collect { llmStreamChunkEither ->
                llmStreamChunkEither.fold(
                    ifLeft = { llmError ->
                        logger.error("LLM API streaming error, provider ${provider.name}: $llmError")
                        onError(llmError)
                    },
                    ifRight = { chunk ->
                        when (chunk) {
                            is LLMStreamChunk.ContentChunk -> {
                                accumulatedContent += chunk.deltaContent
                                onContentDelta(chunk.deltaContent)
                            }

                            is LLMStreamChunk.UsageChunk -> {
                                // Handle usage stats if needed
                            }

                            LLMStreamChunk.Done -> {
                                onStreamComplete(accumulatedContent)
                            }

                            is LLMStreamChunk.Error -> {
                                logger.error("LLM API returned streaming error chunk: ${chunk.llmError}")
                                onError(chunk.llmError)
                            }
                        }
                    }
                )
            }
    }
}
