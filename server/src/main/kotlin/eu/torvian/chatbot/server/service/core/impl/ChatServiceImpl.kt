package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.ChatService
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.MessageEvent
import eu.torvian.chatbot.server.service.core.MessageStreamEvent
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.core.error.model.GetModelError
import eu.torvian.chatbot.server.service.core.error.provider.GetProviderError
import eu.torvian.chatbot.server.service.core.error.settings.GetSettingsByIdError
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.service.security.error.CredentialError
import eu.torvian.chatbot.server.service.tool.ToolExecutorFactory
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Service for processing new chat messages and managing the conversation flow.
 * Handles both streaming and non-streaming LLM interactions, including tool calling loops.
 */
class ChatServiceImpl(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val llmApiClient: LLMApiClient,
    private val toolCallDao: ToolCallDao,
    private val toolExecutorFactory: ToolExecutorFactory,
    private val toolService: ToolService,
    private val llmModelService: LLMModelService,
    private val modelSettingsService: ModelSettingsService,
    private val llmProviderService: LLMProviderService,
    private val credentialManager: CredentialManager,
    private val transactionScope: TransactionScope,
) : ChatService {

    companion object {
        private val logger: Logger = LogManager.getLogger(ChatServiceImpl::class.java)

        /**
         * Maximum number of tool calling iterations to prevent infinite loops.
         * Should be high enough for complex multi-tool tasks but low enough
         * to prevent resource exhaustion.
         */
        private const val MAX_TOOL_CALLING_ITERATIONS = 100
    }

    override suspend fun validateProcessNewMessageRequest(
        sessionId: Long,
        parentMessageId: Long?,
        isStreaming: Boolean
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
                withError({ _: CredentialError.CredentialNotFound ->
                    throw IllegalStateException("API key not found in secure storage for provider ID ${provider.id} (key alias: $keyId)")
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
        session: ChatSession,
        llmConfig: LLMConfig,
        content: String,
        parentMessageId: Long?
    ): Flow<Either<ProcessNewMessageError, MessageEvent>> = channelFlow {
        try {
            // 1. Save user message
            val userMessage = saveUserMessage(session.id, content, parentMessageId)
                .let { (userMessage, updatedParentMessage) ->
                    send(MessageEvent.UserMessageSaved(userMessage, updatedParentMessage).right())
                    userMessage
                }
            // Track the last message ID for parent reference
            var lastMessageId = userMessage.id

            // 2. Build context
            var currentContext: List<RawChatMessage> = buildContext(userMessage, session.messages, session.id)

            // 3. Tool calling loop
            var iterationCount = 0
            while (iterationCount < MAX_TOOL_CALLING_ITERATIONS) {
                iterationCount++
                // 4. Call LLM API with current context and tools
                val llmCompletionResult = llmApiClient.completeChat(
                    messages = currentContext,
                    modelConfig = llmConfig.model,
                    provider = llmConfig.provider,
                    settings = llmConfig.settings,
                    apiKey = llmConfig.apiKey,
                    tools = llmConfig.tools
                ).getOrElse { error ->
                    logger.error("LLM API call failed for session ${session.id}: $error")
                    send(ProcessNewMessageError.ExternalServiceError(error).left())
                    send(MessageEvent.StreamCompleted.right())
                    break
                }

                logger.info("LLM API call successful for session ${session.id}")

                // Check if there are any choices in the response
                val choice = llmCompletionResult.choices.firstOrNull() ?: run {
                    logger.error("LLM API returned successful response with no choices for session ${session.id}")
                    send(
                        ProcessNewMessageError.ExternalServiceError(
                            LLMCompletionError.InvalidResponseError("LLM API returned success but no completion choices.")
                        ).left()
                    )
                    send(MessageEvent.StreamCompleted.right())
                    break
                }

                // 5. Save assistant message and emit event
                val assistantMessage = saveAssistantMessage(
                    sessionId = session.id,
                    content = choice.content ?: "",
                    parentMessageId = lastMessageId,
                    model = llmConfig.model,
                    settings = llmConfig.settings
                ).let { (assistantMessage, updatedParentMessage) ->
                    send(MessageEvent.AssistantMessageSaved(assistantMessage, updatedParentMessage).right())
                    assistantMessage
                }
                // Update last message ID
                lastMessageId = assistantMessage.id

                // 6. Check if LLM wants to call tools
                if (choice.finishReason != "tool_calls" || choice.toolCalls.isNullOrEmpty()) {
                    // Emit stream completed if no tool calls
                    send(MessageEvent.StreamCompleted.right())
                    break
                }

                // Save tool calls to database
                val pendingToolCalls = choice.toolCalls.map { toolCallRequest ->
                    // Find tool definition in enabled list of tools
                    // (If not found, then tool name is hallucinated by the LLM (most likely))
                    val toolDef = llmConfig.tools?.find { it.name == toolCallRequest.name }
                    toolCallDao.insertToolCall(
                        messageId = assistantMessage.id,
                        toolDefinitionId = toolDef?.id,
                        toolName = toolCallRequest.name,
                        toolCallId = toolCallRequest.toolCallId,
                        input = toolCallRequest.arguments,
                        output = null,
                        status = if (toolDef == null) ToolCallStatus.ERROR else ToolCallStatus.PENDING,
                        errorMessage = if (toolDef == null) "Tool '${toolCallRequest.name}' not found in enabled tools" else null,
                        executedAt = Clock.System.now(),
                        durationMs = null
                    ).getOrElse { error ->
                        throw IllegalStateException("Failed to insert tool call: $error")
                    }
                }

                // Emit tool calls received event
                send(MessageEvent.ToolCallsReceived(pendingToolCalls).right())

                // Execute tools and update database (emits events as tools complete)
                // Only execute the valid, pending tool calls
                val completedToolCalls = mutableListOf<ToolCall>()
                executeAndUpdateToolCalls(pendingToolCalls, llmConfig.tools)
                    .collect { completedToolCall ->
                        completedToolCalls.add(completedToolCall)
                        send(MessageEvent.ToolExecutionCompleted(completedToolCall).right())
                    }

                // Add assistant message with tool calls to context
                currentContext = currentContext + RawChatMessage.Assistant(
                    content = choice.content,
                    toolCalls = choice.toolCalls.map { tc ->
                        RawChatMessage.Assistant.ToolCall(
                            id = tc.toolCallId,
                            name = tc.name,
                            arguments = tc.arguments
                        )
                    }
                )

                // Add tool result messages to context
                currentContext = currentContext + completedToolCalls.map { toolCall ->
                    RawChatMessage.Tool(
                        content = if (toolCall.status == ToolCallStatus.ERROR)
                            """{"error":"${toolCall.errorMessage}"}""" else
                            toolCall.output ?: "{}",
                        toolCallId = toolCall.toolCallId ?: "",
                        name = toolCall.toolName
                    )
                }
                // Continue loop for next LLMresponse after tool execution
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
        session: ChatSession,
        llmConfig: LLMConfig,
        content: String,
        parentMessageId: Long?
    ): Flow<Either<ProcessNewMessageError, MessageStreamEvent>> = channelFlow {
        try {
            // Step 1: Save user message
            val userMessage = saveUserMessage(session.id, content, parentMessageId)
                .let { (userMessage, updatedParentMessage) ->
                    send(MessageStreamEvent.UserMessageSaved(userMessage, updatedParentMessage).right())
                    userMessage
                }

            // Step 2: Build context
            var currentContext: List<RawChatMessage> = buildContext(userMessage, session.messages, session.id)
            var lastMessageId = userMessage.id // Track the last message ID for parent reference
            var continueLoop = true
            var iterationCount = 0

            while (continueLoop && iterationCount < MAX_TOOL_CALLING_ITERATIONS) {
                iterationCount++
                // Step 3: Create and save a new empty assistant message to the database
                val assistantMessage = saveAssistantMessage(
                    sessionId = session.id,
                    content = "",
                    parentMessageId = lastMessageId,
                    model = llmConfig.model,
                    settings = llmConfig.settings
                ).let { (assistantMessage, updatedParentMessage) ->
                    send(MessageStreamEvent.AssistantMessageStarted(assistantMessage, updatedParentMessage).right())
                    assistantMessage
                }
                // Update last message ID
                lastMessageId = assistantMessage.id

                // Step 4: Handle LLM streaming with callbacks
                handleLlmStreaming(
                    context = currentContext,
                    model = llmConfig.model,
                    provider = llmConfig.provider,
                    settings = llmConfig.settings,
                    apiKey = llmConfig.apiKey,
                    tools = llmConfig.tools,
                    onContentDelta = { delta ->
                        // Emit content delta to UI
                        send(MessageStreamEvent.AssistantMessageDelta(assistantMessage.id, delta).right())
                    },
                    onToolCallChunk = { toolCallChunk ->
                        // Emit ToolCallDelta events (similar to AssistantMessageDelta)
                        send(
                            MessageStreamEvent.ToolCallDelta(
                                messageId = assistantMessage.id,
                                index = toolCallChunk.index,
                                id = toolCallChunk.id,
                                name = toolCallChunk.name ?: "",
                                argumentsDelta = toolCallChunk.argumentsDelta
                            ).right()
                        )
                    },
                    onStreamComplete = { finalContent, toolCallRequests, finishReason ->
                        // Update the assistant message with the streamed content
                        val updatedAssistantMessage = updateAssistantMessageContent(
                            messageId = assistantMessage.id,
                            content = finalContent
                        )
                        // Emit assistant message finished event
                        send(MessageStreamEvent.AssistantMessageFinished(updatedAssistantMessage).right())

                        // Check if LLM wants to call tools
                        if (finishReason != "tool_calls" || toolCallRequests.isEmpty()) {
                            // No tool calls or final response - stream is complete
                            send(MessageStreamEvent.StreamCompleted.right())
                            continueLoop = false
                            return@handleLlmStreaming
                        }

                        // Save tool calls to database
                        val pendingToolCalls = toolCallRequests.map { toolCallRequest ->
                            // Find tool definition in enabled list of tools
                            // (If not found, then tool name is hallucinated by the LLM (most likely))
                            val toolDef = llmConfig.tools?.find { it.name == toolCallRequest.name }
                            toolCallDao.insertToolCall(
                                messageId = assistantMessage.id,
                                toolDefinitionId = toolDef?.id,
                                toolName = toolCallRequest.name,
                                toolCallId = toolCallRequest.toolCallId,
                                input = toolCallRequest.arguments,
                                output = null,
                                status = if (toolDef == null) ToolCallStatus.ERROR else ToolCallStatus.PENDING,
                                errorMessage = if (toolDef == null) "Tool '${toolCallRequest.name}' not found in enabled tools" else null,
                                executedAt = Clock.System.now(),
                                durationMs = null
                            ).getOrElse { error ->
                                throw IllegalStateException("Failed to insert tool call: $error")
                            }
                        }

                        // Emit tool calls received event
                        send(MessageStreamEvent.ToolCallsReceived(pendingToolCalls).right())

                        // Execute tools and update database (emits events as tools complete)
                        // Only execute the valid, pending tool calls
                        val completedToolCalls = mutableListOf<ToolCall>()
                        executeAndUpdateToolCalls(pendingToolCalls, llmConfig.tools)
                            .collect { completedToolCall ->
                                completedToolCalls.add(completedToolCall)
                                send(MessageStreamEvent.ToolExecutionCompleted(completedToolCall).right())
                            }

                        // Add assistant message with tool calls to context
                        currentContext = currentContext + RawChatMessage.Assistant(
                            content = updatedAssistantMessage.content,
                            toolCalls = toolCallRequests.map { tc ->
                                RawChatMessage.Assistant.ToolCall(
                                    id = tc.toolCallId,
                                    name = tc.name,
                                    arguments = tc.arguments
                                )
                            }
                        )

                        // Add tool result messages to context
                        currentContext = currentContext + completedToolCalls.map { toolCall ->
                            RawChatMessage.Tool(
                                content = if (toolCall.status == ToolCallStatus.ERROR)
                                    """{"error":"${toolCall.errorMessage}"}""" else
                                    toolCall.output ?: "{}",
                                toolCallId = toolCall.toolCallId ?: "",
                                name = toolCall.toolName
                            )
                        }
                        // Continue loop for next LLM response after tool execution
                    },
                    onError = { llmError ->
                        // Emit error and signal termination
                        logger.error("LLM API streaming error for session ${session.id}, provider ${llmConfig.provider.name}: $llmError")
                        send(ProcessNewMessageError.ExternalServiceError(llmError).left())
                        send(MessageStreamEvent.StreamCompleted.right())
                        continueLoop = false
                    }
                )
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
     * Executes tool calls and updates them in the database.
     * Returns a Flow that emits each completed tool call as it finishes execution.
     */
    private fun executeAndUpdateToolCalls(
        pendingToolCalls: List<ToolCall>,
        toolDefinitions: List<ToolDefinition>?
    ): Flow<ToolCall> = flow {
        pendingToolCalls.forEach { pendingToolCall ->
            if (pendingToolCall.status != ToolCallStatus.PENDING) {
                emit(pendingToolCall)
                return@forEach // Skip to next tool call
            }
            val startTime = Clock.System.now()

            // Find tool definition
            val toolDef = toolDefinitions?.find { it.id == pendingToolCall.toolDefinitionId }
                ?: throw IllegalStateException("Tool definition ${pendingToolCall.toolDefinitionId} not found")

            // Get executor for this tool type
            val executor = toolExecutorFactory.getExecutor(toolDef.type).getOrElse { error ->
                throw IllegalStateException("Failed to get executor for tool type ${toolDef.type}: $error")
            }

            // Execute the tool
            // Pass raw JSON string to executor (may be invalid)
            // The executor is responsible for parsing and validating the input
            val result = executor.executeTool(toolDef, pendingToolCall.input)

            val endTime = Clock.System.now()
            val durationMs = (endTime - startTime).inWholeMilliseconds

            // Update tool call in database
            val updatedToolCall = result.fold(
                ifLeft = { error ->
                    pendingToolCall.copy(
                        status = ToolCallStatus.ERROR,
                        errorMessage = error.toString(),
                        durationMs = durationMs
                    )
                },
                ifRight = { output ->
                    pendingToolCall.copy(
                        output = output,
                        status = ToolCallStatus.SUCCESS,
                        durationMs = durationMs
                    )
                }
            )

            // Update in database
            toolCallDao.updateToolCall(updatedToolCall).getOrElse { error ->
                throw IllegalStateException("Failed to update tool call: $error")
            }

            // Emit the updated tool call
            emit(updatedToolCall)
        }
    }

    /**
     * Builds the conversation context for an LLM request.
     *
     * This method constructs a list of [RawChatMessage] objects representing the conversation
     * thread from root to the current user message. It includes:
     * - User messages
     * - Assistant messages (with linked tool calls)
     * - Tool result messages (reconstructed from ToolCall records)
     *
     * The context includes only messages in the current thread (following parentMessageId
     * links from current to root), not all messages in the session. This supports branching
     * conversations where users can create alternative response paths.
     *
     * @param userMessage The current user message (the one being processed)
     * @param sessionMessages All messages in the session (for efficient lookup)
     * @param sessionId The session ID (for fetching tool calls)
     * @return List of [RawChatMessage] objects in chronological order (root to current)
     */
    private suspend fun buildContext(
        userMessage: ChatMessage.UserMessage,
        sessionMessages: List<ChatMessage>,
        sessionId: Long
    ): List<RawChatMessage> {
        // Fetch all tool calls for the session, sorted by id
        val allToolCalls = toolCallDao.getToolCallsBySessionId(sessionId).sortedBy { it.id }

        // Build message map for efficient lookup
        val messageMap = sessionMessages.associateBy { it.id }

        // Traverse up the tree from the current user message to build the thread
        val threadMessages = mutableListOf<ChatMessage>()
        var currentMessage: ChatMessage? = userMessage
        val visitedIds = mutableSetOf<Long>()

        while (currentMessage != null && !visitedIds.contains(currentMessage.id)) {
            visitedIds.add(currentMessage.id)
            threadMessages.add(currentMessage)
            currentMessage = currentMessage.parentMessageId?.let { messageMap[it] }
        }

        // Reverse to get chronological order (root to current)
        threadMessages.reverse()

        // Convert thread messages to RawChatMessage objects
        val rawContext = mutableListOf<RawChatMessage>()

        threadMessages.forEach { message ->
            when (message) {
                is ChatMessage.UserMessage -> {
                    // Convert user message
                    rawContext.add(RawChatMessage.User(message.content))
                }

                is ChatMessage.AssistantMessage -> {
                    // Get tool calls for this message
                    val messageToolCalls = allToolCalls.filter { it.messageId == message.id }

                    // Convert assistant message with tool calls
                    val toolCalls = if (messageToolCalls.isNotEmpty()) {
                        messageToolCalls.map { tc ->
                            RawChatMessage.Assistant.ToolCall(
                                id = tc.toolCallId,
                                name = tc.toolName,
                                arguments = tc.input
                            )
                        }
                    } else {
                        null
                    }

                    rawContext.add(
                        RawChatMessage.Assistant(
                            content = message.content,
                            toolCalls = toolCalls
                        )
                    )

                    // Add tool result messages for completed tool calls
                    messageToolCalls
                        .forEach { toolCall ->
                            rawContext.add(
                                RawChatMessage.Tool(
                                    content = toolCall.output ?: "{}",
                                    toolCallId = toolCall.toolCallId ?: "",
                                    name = toolCall.toolName
                                )
                            )
                        }
                }
            }
        }

        return rawContext
    }

    /**
     * Saves user message and updates relationships.
     */
    private suspend fun saveUserMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long?
    ): Pair<ChatMessage.UserMessage, ChatMessage?> = transactionScope.transaction {
        // 1. Insert user message (DAO will handle child linking atomically when parent is provided)
        val userMessage = messageDao.insertUserMessage(sessionId, content, parentMessageId).getOrElse { daoError ->
            throw IllegalStateException(
                "Failed to insert user message. " +
                        "Session id: $sessionId. Parent message id: $parentMessageId. Error: $daoError"
            )
        }

        // 2. Update session's leaf message ID
        sessionDao.updateSessionLeafMessageId(sessionId, userMessage.id).getOrElse { updateError ->
            throw IllegalStateException(
                "Failed to update session leaf message ID. " +
                        "Session id: $sessionId. New leaf message id: ${userMessage.id}. Error: $updateError"
            )
        }

        // 3. Retrieve updated parent message if provided
        val updatedParentMsg = parentMessageId?.let { id ->
            messageDao.getMessageById(id).getOrElse { daoError ->
                throw IllegalStateException(
                    "Failed to retrieve updated parent message. " +
                            "Parent message id: $id. Error: $daoError"
                )
            }
        }

        // 4. Return user message and updated parent
        userMessage to updatedParentMsg
    }

    /**
     * Saves assistant message and updates relationships.
     */
    private suspend fun saveAssistantMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long,
        model: LLMModel,
        settings: ChatModelSettings
    ): Pair<ChatMessage.AssistantMessage, ChatMessage> =
        transactionScope.transaction {
            // 1. Insert assistant message (DAO will handle child linking atomically)
            val assistantMsg = messageDao.insertAssistantMessage(
                sessionId, content, parentMessageId, model.id, settings.id
            ).getOrElse { daoError ->
                throw IllegalStateException(
                    "Failed to insert assistant message. " +
                            "Session id: $sessionId. Parent message id: $parentMessageId. Error: $daoError"
                )
            }

            // 2. Update session's leaf message ID
            sessionDao.updateSessionLeafMessageId(sessionId, assistantMsg.id).getOrElse { updateError ->
                throw IllegalStateException(
                    "Failed to update session leaf message ID. " +
                            "Session id: $sessionId. New leaf message id: ${assistantMsg.id}. Error: $updateError"
                )
            }

            // 3. Retrieve updated parent message
            val updatedParentMsg = messageDao.getMessageById(parentMessageId).getOrElse { daoError ->
                throw IllegalStateException(
                    "Failed to retrieve updated parent message. " +
                            "Parent message id: $parentMessageId. Error: $daoError"
                )
            }

            // 4. Return assistant message and updated parent
            assistantMsg to updatedParentMsg
        }

    /**
     * Updates the content of an assistant message.
     */
    private suspend fun updateAssistantMessageContent(
        messageId: Long,
        content: String
    ): ChatMessage.AssistantMessage = transactionScope.transaction {
        // Update the message content in the database
        messageDao.updateMessageContent(messageId, content).getOrElse { error ->
            throw IllegalStateException("Failed to update assistant message content: $error")
        } as ChatMessage.AssistantMessage
    }

    /**
     * Handles LLM streaming response and emits updates.
     *
     * @param context The conversation context as RawChatMessage objects
     * @param model The LLM model to use
     * @param provider The LLM provider to use
     * @param settings The model settings to use
     * @param apiKey The API key to use (nullable if not required by the provider)
     * @param tools The list of enabled tools for this session (nullable if no tools are enabled)
     * @param onContentDelta Callback for assistant message content deltas.
     * @param onToolCallChunk Callback for tool call argument deltas
     * @param onStreamComplete Callback for stream completion (includes final content and tool calls)
     * @param onError Callback for stream errors
     */
    private suspend fun handleLlmStreaming(
        context: List<RawChatMessage>,
        model: LLMModel,
        provider: LLMProvider,
        settings: ChatModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?,
        onContentDelta: suspend (deltaContent: String) -> Unit,
        onToolCallChunk: suspend (toolCallChunk: LLMStreamChunk.ToolCallChunk) -> Unit,
        onStreamComplete: suspend (finalContent: String, toolCallRequests: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>, finishReason: String?) -> Unit,
        onError: suspend (error: LLMCompletionError) -> Unit
    ) {
        val accumulatedContent = StringBuilder()
        var finishReason: String? = null

        // Map to accumulate tool calls by index (for OpenAI streaming)
        val toolCallsByIndex = mutableMapOf<Int, MutableToolCallAccumulator>()

        llmApiClient.completeChatStreaming(context, model, provider, settings, apiKey, tools)
            .collect { llmStreamChunkEither ->
                llmStreamChunkEither.fold(
                    ifLeft = { llmError ->
                        logger.error("LLM API streaming error, provider ${provider.name}: $llmError")
                        onError(llmError)
                    },
                    ifRight = { chunk ->
                        when (chunk) {
                            is LLMStreamChunk.ContentChunk -> {
                                accumulatedContent.append(chunk.deltaContent)
                                onContentDelta(chunk.deltaContent)
                                if (chunk.finishReason != null) {
                                    finishReason = chunk.finishReason
                                }
                            }

                            is LLMStreamChunk.ToolCallChunk -> {
                                // Accumulate tool call chunks by index
                                val index = chunk.index ?: 0 // Default to 0 for Ollama
                                val accumulator = toolCallsByIndex.getOrPut(index) {
                                    MutableToolCallAccumulator(
                                        id = chunk.id,
                                        name = chunk.name ?: "",
                                        arguments = StringBuilder()
                                    )
                                }

                                // Update ID and name if provided (first chunk usually has them)
                                if (chunk.id != null && accumulator.id == null) {
                                    accumulator.id = chunk.id
                                }
                                if (!chunk.name.isNullOrEmpty() && accumulator.name.isEmpty()) {
                                    accumulator.name = chunk.name
                                }

                                // Append arguments delta
                                if (chunk.argumentsDelta != null) {
                                    accumulator.arguments.append(chunk.argumentsDelta)
                                }

                                // Emit the chunk to UI
                                onToolCallChunk(chunk)
                            }

                            is LLMStreamChunk.UsageChunk -> {
                                // Handle usage stats if needed
                                logger.debug("Usage stats: prompt=${chunk.promptTokens}, completion=${chunk.completionTokens}, total=${chunk.totalTokens}")
                            }

                            LLMStreamChunk.Done -> {
                                // Build toolCallRequests first, as they are now passed to onStreamComplete
                                val toolCallRequests = if (toolCallsByIndex.isNotEmpty()) {
                                    toolCallsByIndex.values.map { accumulator ->
                                        LLMCompletionResult.CompletionChoice.ToolCallRequest(
                                            name = accumulator.name,
                                            arguments = accumulator.arguments.toString().takeIf { it.isNotEmpty() },
                                            toolCallId = accumulator.id
                                        )
                                    }
                                } else {
                                    emptyList()
                                }

                                // Call onStreamComplete with final content, tool calls, and finish reason
                                onStreamComplete(accumulatedContent.toString(), toolCallRequests, finishReason)
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

    /**
     * Helper class to accumulate tool call data during streaming.
     */
    private data class MutableToolCallAccumulator(
        var id: String?,
        var name: String,
        val arguments: StringBuilder
    )
}

