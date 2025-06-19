package eu.torvian.chatbot.server.service.core.impl

import arrow.core.*
import arrow.core.raise.*
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.*
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.MessageService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.message.*
import eu.torvian.chatbot.server.service.core.error.model.*
import eu.torvian.chatbot.server.service.core.error.provider.*
import eu.torvian.chatbot.server.service.core.error.settings.*
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.service.security.error.CredentialError
import eu.torvian.chatbot.server.utils.transactions.TransactionScope

/**
 * Implementation of the [MessageService] interface.
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

    override suspend fun getMessagesBySessionId(sessionId: Long): List<ChatMessage> {
        return transactionScope.transaction {
            messageDao.getMessagesBySessionId(sessionId)
        }
    }

    override suspend fun processNewMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long?
    ): Either<ProcessNewMessageError, List<ChatMessage>> =
        transactionScope.transaction {
            either {

                // 1. Validate session
                val session = withError({ daoError: SessionError.SessionNotFound ->
                    ProcessNewMessageError.SessionNotFound(daoError.id)
                }) {
                    sessionDao.getSessionById(sessionId).bind()
                }

                // 2. Save user message
                val userMessage = withError({ daoError: InsertMessageError ->
                    when (daoError) {
                        is InsertMessageError.SessionNotFound -> {
                            throw IllegalStateException("Session $sessionId not found after validation")
                        }
                        is InsertMessageError.ParentNotInSession -> {
                            ProcessNewMessageError.ParentNotInSession(daoError.sessionId, daoError.parentId)
                        }
                    }
                }) {
                    messageDao.insertUserMessage(sessionId, content, parentMessageId).bind()
                }

                // 3. Add user message as child to parent
                if (parentMessageId != null) {
                    withError({ daoError: MessageAddChildError ->
                        throw IllegalStateException("Failed to add user message as child to parent: ${daoError.javaClass.simpleName}")
                    }) {
                        messageDao.addChildToMessage(parentMessageId, userMessage.id).bind()
                    }
                }

                // 4. Get model and settings config
                val modelId = session.currentModelId
                    ?: raise(ProcessNewMessageError.ModelConfigurationError("No model selected for session"))
                val settingsId = session.currentSettingsId
                    ?: raise(ProcessNewMessageError.ModelConfigurationError("No settings selected for session"))

                // Fetch Model
                val model = withError({ serviceError: GetModelError.ModelNotFound ->
                    throw IllegalStateException("Model with ID $modelId not found after validation")
                }) {
                    llmModelService.getModelById(modelId).bind()
                }

                // Fetch Settings
                val settings = withError({ serviceError: GetSettingsByIdError ->
                    when (serviceError) {
                        is GetSettingsByIdError.SettingsNotFound -> {
                            throw IllegalStateException("Settings with ID $settingsId not found after validation")
                        }
                    }
                }) {
                    modelSettingsService.getSettingsById(settingsId).bind()
                }

                // Get provider and API Key from the model's provider (if the provider requires one)
                val (provider, apiKey) = run {
                    // First get the provider for this model
                    val provider = withError({ serviceError: GetProviderError.ProviderNotFound ->
                        throw IllegalStateException("Provider not found for model ID $modelId (provider ID: ${model.providerId})")
                    }) {
                        llmProviderService.getProviderById(model.providerId).bind()
                    }

                    // Then get the credential using the provider's API key ID (if it has one)
                    val apiKey = provider.apiKeyId?.let { keyId ->
                        withError({ credError: CredentialError.CredentialNotFound ->
                            throw IllegalStateException("API key not found in secure storage for provider ID ${provider.id} (key alias: $keyId)")
                        }) {
                            credentialManager.getCredential(keyId).bind()
                        }
                    }

                    provider to apiKey
                }

                // 5. Build context
                val allMessagesInSession = messageDao.getMessagesBySessionId(sessionId)
                val context = buildContext(userMessage, allMessagesInSession)

                // 6. Call LLM API
                val llmResponse = withError({ externalErrorMsg: String ->
                    ProcessNewMessageError.ExternalServiceError("LLM API Error: $externalErrorMsg")
                }) {
                    llmApiClient.completeChat(
                        messages = context,
                        modelConfig = model,
                        provider = provider,
                        settings = settings,
                        apiKey = apiKey
                    ).bind()
                }

                // 7. Save assistant message
                val assistantMessageContent =
                    llmResponse.choices.firstOrNull()?.message?.content ?: "Error: Empty LLM response"
                val assistantMessage = withError({ daoError: InsertMessageError ->
                    throw IllegalStateException("Failed to insert assistant message: ${daoError.javaClass.simpleName}")
                }) {
                    messageDao.insertAssistantMessage(
                        sessionId,
                        assistantMessageContent,
                        userMessage.id,
                        modelId,
                        settingsId
                    ).bind()
                }

                // 8. Add assistant message as child to user message
                withError({ daoError: MessageAddChildError ->
                    throw IllegalStateException("Failed to add assistant message as child to user message: ${daoError.javaClass.simpleName}")
                }) {
                    messageDao.addChildToMessage(userMessage.id, assistantMessage.id).bind()
                }

                // 9. Update session's leaf message ID
                withError({ daoError: SessionError ->
                    throw IllegalStateException("Failed to update session leaf message ID: ${daoError.javaClass.simpleName}")
                }) {
                    sessionDao.updateSessionLeafMessageId(sessionId, assistantMessage.id).bind()
                }

                // 10. Return new messages as the success value
                listOf(userMessage, assistantMessage)
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
     * Builds the context for the LLM API call.
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
            context.add(0, c)
            c = c.parentMessageId?.let { messageMap[it] }
        }
        return context
    }
}