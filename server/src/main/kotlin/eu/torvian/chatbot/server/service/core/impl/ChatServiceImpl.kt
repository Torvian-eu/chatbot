package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.tool.ToolCallApprovalResponse
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.data.dao.UserToolApprovalPreferenceDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.core.error.model.GetModelError
import eu.torvian.chatbot.server.service.core.error.provider.GetProviderError
import eu.torvian.chatbot.server.service.core.error.settings.GetSettingsByIdError
import eu.torvian.chatbot.server.service.llm.*
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutor
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutorEvent
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.service.security.error.CredentialError
import eu.torvian.chatbot.server.service.tool.ToolExecutorFactory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.*
import java.util.concurrent.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

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
    private val localMcpExecutor: LocalMCPExecutor,
    private val userToolApprovalPreferenceDao: UserToolApprovalPreferenceDao,
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
        mcpResponseFlow: Flow<LocalMCPToolCallResult>,
        approvalResponseFlow: Flow<ToolCallApprovalResponse>
    ): Flow<Either<ProcessNewMessageError, MessageEvent>> = channelFlow {
        try {
            // 1. Save user message (only if content is provided)
            // Track the last message ID for parent reference and build updated message list
            var lastMessageId: Long
            val updatedSessionMessages = if (content != null) {
                val userMessage = saveUserMessage(session.id, content, parentMessageId, fileReferences)
                    .let { (userMessage, updatedParentMessage) ->
                        send(MessageEvent.UserMessageSaved(userMessage, updatedParentMessage).right())
                        userMessage
                    }
                lastMessageId = userMessage.id
                // Append newly created user message to session.messages so buildContext can find it
                session.messages + userMessage
            } else {
                // Branch & Continue mode: no new user message, start from parentMessageId
                if (parentMessageId == null) {
                    throw IllegalStateException("parentMessageId is null in Branch & Continue mode")
                }
                lastMessageId = parentMessageId
                session.messages
            }

            // 2. Build context
            var currentContext: List<RawChatMessage> = buildContext(lastMessageId, updatedSessionMessages, session.id)

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
                        denialReason = null,
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
                executeAndUpdateToolCalls(
                    userId,
                    pendingToolCalls,
                    llmConfig.tools,
                    mcpResponseFlow,
                    approvalResponseFlow
                )
                    .collect { event ->
                        when (event) {
                            is ToolExecutionEvent.ToolCallExecuting -> {
                                send(MessageEvent.ToolCallExecuting(event.toolCall).right())
                            }

                            is ToolExecutionEvent.ToolCallCompleted -> {
                                completedToolCalls.add(event.toolCall)
                                send(MessageEvent.ToolExecutionCompleted(event.toolCall).right())
                            }

                            is ToolExecutionEvent.LocalMCPToolCallReceived -> {
                                send(MessageEvent.LocalMCPToolCallReceived(event.request).right())
                            }

                            is ToolExecutionEvent.ToolCallApprovalRequested -> {
                                send(MessageEvent.ToolCallApprovalRequested(event.toolCall).right())
                            }
                        }
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
                        content = buildToolResultContent(toolCall),
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
        userId: Long,
        session: ChatSession,
        llmConfig: LLMConfig,
        content: String?,
        parentMessageId: Long?,
        fileReferences: List<FileReference>,
        mcpResponseFlow: Flow<LocalMCPToolCallResult>,
        approvalResponseFlow: Flow<ToolCallApprovalResponse>
    ): Flow<Either<ProcessNewMessageError, MessageStreamEvent>> = channelFlow {
        try {
            // Step 1: Save user message (only if content is provided)
            // Track the last message ID for parent reference and build updated message list
            var lastMessageId: Long
            val updatedSessionMessages = if (content != null) {
                val userMessage = saveUserMessage(session.id, content, parentMessageId, fileReferences)
                    .let { (userMessage, updatedParentMessage) ->
                        send(MessageStreamEvent.UserMessageSaved(userMessage, updatedParentMessage).right())
                        userMessage
                    }
                lastMessageId = userMessage.id
                // Append newly created user message to session.messages so buildContext can find it
                session.messages + userMessage
            } else {
                // Branch & Continue mode: no new user message, start from parentMessageId
                if (parentMessageId == null) {
                    throw IllegalStateException("parentMessageId is null in Branch & Continue mode")
                }
                lastMessageId = parentMessageId
                session.messages
            }

            // Step 2: Build context
            var currentContext: List<RawChatMessage> = buildContext(lastMessageId, updatedSessionMessages, session.id)
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
                                denialReason = null,
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
                        executeAndUpdateToolCalls(
                            userId,
                            pendingToolCalls,
                            llmConfig.tools,
                            mcpResponseFlow,
                            approvalResponseFlow
                        )
                            .collect { event ->
                                when (event) {
                                    is ToolExecutionEvent.ToolCallExecuting -> {
                                        send(MessageStreamEvent.ToolCallExecuting(event.toolCall).right())
                                    }

                                    is ToolExecutionEvent.ToolCallCompleted -> {
                                        completedToolCalls.add(event.toolCall)
                                        send(MessageStreamEvent.ToolExecutionCompleted(event.toolCall).right())
                                    }

                                    is ToolExecutionEvent.LocalMCPToolCallReceived -> {
                                        send(MessageStreamEvent.LocalMCPToolCallReceived(event.request).right())
                                    }

                                    is ToolExecutionEvent.ToolCallApprovalRequested -> {
                                        send(MessageStreamEvent.ToolCallApprovalRequested(event.toolCall).right())
                                    }
                                }
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
                                content = buildToolResultContent(toolCall),
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
                    },
                    onCancellation = { partialContent ->
                        // Save partial content to database on cancellation
                        if (partialContent.isNotEmpty()) {
                            logger.info("Saving partial content for cancelled message ${assistantMessage.id}: ${partialContent.length} characters")
                            updateAssistantMessageContent(
                                messageId = assistantMessage.id,
                                content = partialContent
                            )
                        } else {
                            logger.info("No partial content to save for cancelled message ${assistantMessage.id}")
                        }
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
     * Executes tool calls sequentially and updates them in the database.
     * Returns a Flow that emits events for each tool call, which can be either a request
     * for client-side execution or a notification of completion.
     * Checks user approval preferences first; if not found, requests manual approval.
     */
    private fun executeAndUpdateToolCalls(
        userId: Long,
        pendingToolCalls: List<ToolCall>,
        toolDefinitions: List<ToolDefinition>?,
        mcpResponseFlow: Flow<LocalMCPToolCallResult>,
        approvalResponseFlow: Flow<ToolCallApprovalResponse>
    ): Flow<ToolExecutionEvent> = channelFlow {
        val mcpStartTimes = mutableMapOf<Long, Instant>()

        pendingToolCalls.forEach { pendingToolCall ->
            if (pendingToolCall.status != ToolCallStatus.PENDING) {
                send(ToolExecutionEvent.ToolCallCompleted(pendingToolCall))
                return@forEach // Skip to next tool call
            }

            // Find tool definition
            val toolDef = toolDefinitions?.find { it.id == pendingToolCall.toolDefinitionId }
                ?: throw IllegalStateException("Tool definition ${pendingToolCall.toolDefinitionId} not found for pending tool call")

            // Step 1: Check for auto-approval preference
            val preference = toolDef.id.let { toolDefId ->
                transactionScope.transaction {
                    userToolApprovalPreferenceDao.getPreference(userId, toolDefId).getOrNull()
                }
            }

            val approvalResponse = if (preference != null) {
                // Auto-approve or auto-deny based on preference
                logger.info("Auto-${if (preference.autoApprove) "approving" else "denying"} tool call ${pendingToolCall.id} for user $userId based on preference")

                if (!preference.autoApprove) {
                    // Auto-deny - update status and skip execution
                    val deniedToolCall = pendingToolCall.copy(
                        status = ToolCallStatus.USER_DENIED,
                        denialReason = preference.denialReason ?: "Auto-denied by user preference"
                    )
                    toolCallDao.updateToolCall(deniedToolCall).getOrElse { error ->
                        throw IllegalStateException("Failed to update auto-denied tool call: $error")
                    }
                    send(ToolExecutionEvent.ToolCallCompleted(deniedToolCall))
                    return@forEach
                }

                // Auto-approved - create synthetic approval response
                ToolCallApprovalResponse(
                    toolCallId = pendingToolCall.id,
                    approved = true,
                    denialReason = null
                )
            } else {
                // No preference found - request manual approval
                // Step 1a: Update tool call to AWAITING_APPROVAL
                val awaitingApprovalToolCall = pendingToolCall.copy(status = ToolCallStatus.AWAITING_APPROVAL)
                toolCallDao.updateToolCall(awaitingApprovalToolCall).getOrElse { error ->
                    throw IllegalStateException("Failed to update tool call to AWAITING_APPROVAL: $error")
                }

                // Send the tool call with AWAITING_APPROVAL status to the client
                send(ToolExecutionEvent.ToolCallApprovalRequested(awaitingApprovalToolCall))

                // Step 1b: Wait for approval response
                try {
                    withTimeout(300_000) { // 5 minute timeout
                        approvalResponseFlow.first { it.toolCallId == pendingToolCall.id }
                    }
                } catch (_: TimeoutCancellationException) {
                    // Timeout - treat as denial
                    val deniedToolCall = pendingToolCall.copy(
                        status = ToolCallStatus.USER_DENIED,
                        denialReason = "Approval timeout (no response within 5 minutes)"
                    )
                    toolCallDao.updateToolCall(deniedToolCall).getOrElse { error ->
                        throw IllegalStateException("Failed to update tool call after timeout: $error")
                    }
                    send(ToolExecutionEvent.ToolCallCompleted(deniedToolCall))
                    return@forEach
                }
            }

            // Step 2: Handle approval decision
            if (!approvalResponse.approved) {
                // User denied - update status and skip execution
                val deniedToolCall = pendingToolCall.copy(
                    status = ToolCallStatus.USER_DENIED,
                    denialReason = approvalResponse.denialReason
                )
                toolCallDao.updateToolCall(deniedToolCall).getOrElse { error ->
                    throw IllegalStateException("Failed to update denied tool call: $error")
                }
                send(ToolExecutionEvent.ToolCallCompleted(deniedToolCall))
                return@forEach
            }

            // Tool approved - update to EXECUTING and proceed with execution
            val executingToolCall = pendingToolCall.copy(status = ToolCallStatus.EXECUTING)
            toolCallDao.updateToolCall(executingToolCall).getOrElse { error ->
                throw IllegalStateException("Failed to update tool call to EXECUTING: $error")
            }
            send(ToolExecutionEvent.ToolCallExecuting(executingToolCall))

            when (toolDef) {
                is LocalMCPToolDefinition -> {
                    localMcpExecutor.executeTool(
                        toolDefinition = toolDef,
                        toolCallId = pendingToolCall.id,
                        inputJson = pendingToolCall.input,
                        responseFlow = mcpResponseFlow
                    ).collect { event ->
                        when (event) {
                            is LocalMCPExecutorEvent.ToolExecutionRequest -> {
                                mcpStartTimes[event.request.toolCallId] = Clock.System.now()
                                send(ToolExecutionEvent.LocalMCPToolCallReceived(event.request))
                            }

                            is LocalMCPExecutorEvent.ToolExecutionResult -> {
                                val startTime = mcpStartTimes.remove(event.result.toolCallId) ?: Clock.System.now()
                                val durationMs = (Clock.System.now() - startTime).inWholeMilliseconds
                                val updatedToolCall = pendingToolCall.copy(
                                    output = event.result.output,
                                    status = if (event.result.isError) ToolCallStatus.ERROR else ToolCallStatus.SUCCESS,
                                    errorMessage = event.result.errorMessage,
                                    durationMs = durationMs
                                )
                                toolCallDao.updateToolCall(updatedToolCall).getOrElse { error ->
                                    throw IllegalStateException("Failed to update tool call: $error")
                                }
                                send(ToolExecutionEvent.ToolCallCompleted(updatedToolCall))
                            }

                            is LocalMCPExecutorEvent.ToolExecutionError -> {
                                val startTime = mcpStartTimes.remove(event.toolCallId) ?: Clock.System.now()
                                val durationMs = (Clock.System.now() - startTime).inWholeMilliseconds
                                val updatedToolCall = pendingToolCall.copy(
                                    status = ToolCallStatus.ERROR,
                                    errorMessage = event.error.message,
                                    durationMs = durationMs
                                )
                                toolCallDao.updateToolCall(updatedToolCall).getOrElse { error ->
                                    throw IllegalStateException("Failed to update tool call: $error")
                                }
                                send(ToolExecutionEvent.ToolCallCompleted(updatedToolCall))
                            }
                        }
                    }
                }

                else -> { // Non-MCP tool
                    val startTime = Clock.System.now()
                    // Get executor for this tool type
                    val executor = toolExecutorFactory.getExecutor(toolDef.type).getOrElse { error ->
                        throw IllegalStateException("Failed to get executor for tool type ${toolDef.type}: $error")
                    }

                    // Execute the tool
                    val result = executor.executeTool(toolDef, pendingToolCall.input)
                    val endTime = Clock.System.now()
                    val durationMs = (endTime - startTime).inWholeMilliseconds

                    // Create updated tool call from result
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

                    // Emit the completed tool call
                    send(ToolExecutionEvent.ToolCallCompleted(updatedToolCall))
                }
            }
        }
    }

    /**
     * Builds the conversation context for an LLM request.
     *
     * This method constructs a list of [RawChatMessage] objects representing the conversation
     * thread from root to the starting message. It includes:
     * - User messages
     * - Assistant messages (with linked tool calls)
     * - Tool result messages (reconstructed from ToolCall records)
     *
     * The context includes only messages in the current thread (following parentMessageId
     * links from current to root), not all messages in the session. This supports branching
     * conversations where users can create alternative response paths.
     *
     * @param startingMessageId The ID of the message to start building context from (inclusive)
     * @param sessionMessages All messages in the session (for efficient lookup)
     * @param sessionId The session ID (for fetching tool calls)
     * @return List of [RawChatMessage] objects in chronological order (root to starting message)
     */
    private suspend fun buildContext(
        startingMessageId: Long,
        sessionMessages: List<ChatMessage>,
        sessionId: Long
    ): List<RawChatMessage> {
        // Fetch all tool calls for the session, sorted by id
        val allToolCalls = toolCallDao.getToolCallsBySessionId(sessionId).sortedBy { it.id }

        // Build message map for efficient lookup
        val messageMap = sessionMessages.associateBy { it.id }

        // Traverse up the tree from the starting message to build the thread
        val threadMessages = mutableListOf<ChatMessage>()
        var currentMessage: ChatMessage? = messageMap[startingMessageId]
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
                    // Convert user message with file references embedded in content
                    val contentWithFileRefs = buildContentWithFileReferences(message.content, message.fileReferences)
                    rawContext.add(RawChatMessage.User(contentWithFileRefs))
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

                    // Only add results for completed/errored/denied tools
                    messageToolCalls
                        .filter {
                            it.status in setOf(
                                ToolCallStatus.SUCCESS,
                                ToolCallStatus.ERROR,
                                ToolCallStatus.USER_DENIED
                            )
                        }
                        .forEach { toolCall ->
                            rawContext.add(
                                RawChatMessage.Tool(
                                    content = buildToolResultContent(toolCall),
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
        parentMessageId: Long?,
        fileReferences: List<FileReference> = emptyList()
    ): Pair<ChatMessage.UserMessage, ChatMessage?> = transactionScope.transaction {
        // 1. Insert user message (DAO will handle child linking atomically when parent is provided)
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
                "Failed to insert user message. " +
                        "Session id: $sessionId. Parent message id: $parentMessageId. Error: $daoError"
            )
        } as ChatMessage.UserMessage

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
            val assistantMsg = messageDao.insertMessage(
                sessionId = sessionId,
                targetMessageId = parentMessageId,
                position = MessageInsertPosition.APPEND,
                role = ChatMessage.Role.ASSISTANT,
                content = content,
                modelId = model.id,
                settingsId = settings.id
            ).getOrElse { daoError ->
                throw IllegalStateException(
                    "Failed to insert assistant message. " +
                            "Session id: $sessionId. Parent message id: $parentMessageId. Error: $daoError"
                )
            } as ChatMessage.AssistantMessage

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
     * @param onCancellation Callback for stream cancellation (receives accumulated partial content)
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
        onError: suspend (error: LLMCompletionError) -> Unit,
        onCancellation: suspend (partialContent: String) -> Unit
    ) {
        val accumulatedContent = StringBuilder()
        var finishReason: String? = null

        // Map to accumulate tool calls by index (for OpenAI streaming)
        val toolCallsByIndex = mutableMapOf<Int, MutableToolCallAccumulator>()

        try {
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

                                    if (finishReason == null && toolCallRequests.isNotEmpty()) {
                                        finishReason = "tool_calls"
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
        } catch (e: CancellationException) {
            // Stream was cancelled - invoke callback with accumulated partial content inside NonCancellable context
            logger.info("LLM streaming cancelled, accumulated content length: ${accumulatedContent.length}")
            try {
                withContext(NonCancellable) {
                    onCancellation(accumulatedContent.toString())
                }
            } catch (handlerError: Exception) {
                logger.error("Failed to run onCancellation handler: ${handlerError.message}", handlerError)
            }
            // Re-throw the cancellation exception to propagate it
            throw e
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

    /**
     * Private sealed interface to represent events from the tool execution logic.
     * This allows `executeAndUpdateToolCalls` to communicate different outcomes
     * (a completed tool call vs. a request for client-side execution vs. approval request) to its caller.
     */
    private sealed interface ToolExecutionEvent {
        data class ToolCallExecuting(val toolCall: ToolCall) : ToolExecutionEvent
        data class ToolCallCompleted(val toolCall: ToolCall) : ToolExecutionEvent
        data class LocalMCPToolCallReceived(val request: LocalMCPToolCallRequest) : ToolExecutionEvent
        data class ToolCallApprovalRequested(val toolCall: ToolCall) : ToolExecutionEvent
    }

    /**
     * Builds the content for a tool result message based on the tool call status and output.
     *
     * @param toolCall The tool call record to build content for
     */
    private fun buildToolResultContent(toolCall: ToolCall): String {
        return when (toolCall.status) {
            ToolCallStatus.ERROR -> {
                buildJsonObject {
                    put("error", toolCall.errorMessage ?: "Unknown error")
                }.toString()
            }

            ToolCallStatus.USER_DENIED -> {
                buildJsonObject {
                    put("user_denied", "Tool call was denied by user.")
                    put("reason", toolCall.denialReason ?: "No reason provided")
                }.toString()
            }

            else -> {
                // For SUCCESS or any other completed status, if output exists use it as-is.
                // If output is null or blank, provide an empty JSON object.
                val output = toolCall.output
                if (output.isNullOrBlank()) {
                    buildJsonObject { }.toString()
                } else {
                    output
                }
            }
        }
    }

    /**
     * Builds the message content with file references for LLM context.
     *
     * For inline references (with inlinePosition set):
     * - Inserts the file content at the specified position with a header showing the file path
     * - Or inserts a reference placeholder if no content is included
     *
     * For non-inline references:
     * - Appends a list of referenced files at the end of the message
     * - Includes file content if available, otherwise just metadata
     *
     * @param content The original message content
     * @param fileReferences The list of file references to include
     * @return The content with file references embedded
     */
    private fun buildContentWithFileReferences(
        content: String,
        fileReferences: List<FileReference>
    ): String {
        if (fileReferences.isEmpty()) return content

        // Separate inline and non-inline references
        val inlineRefs = fileReferences.filter { it.isInline }.sortedByDescending { it.inlinePosition ?: 0 }
        val nonInlineRefs = fileReferences.filter { !it.isInline }

        var result = content

        // Helper function to format file header with metadata
        fun formatFileHeader(ref: FileReference): String {
            val header = buildString {
                append("--- ${ref.relativePath}")
                append(" [${formatFileSize(ref.fileSize)}, ${ref.mimeType}, ${formatLastModified(ref.lastModified)}]")
                append(" ---")
            }
            return header
        }

        // Helper function to format file reference without content
        fun formatFileReference(ref: FileReference): String {
            return "[reference: ${ref.relativePath} (${formatFileSize(ref.fileSize)}, ${ref.mimeType})]"
        }

        // Process inline references (in reverse order to maintain positions)
        for (ref in inlineRefs) {
            val position = ref.inlinePosition ?: continue
            val insertion = if (ref.content != null) {
                "\n${formatFileHeader(ref)}\n${ref.content}\n--- end ${ref.fileName} ---\n"
            } else {
                "\n${formatFileReference(ref)}\n"
            }

            // Insert at position, clamped to valid range
            val insertPos = position.coerceIn(0, result.length)
            result = result.take(insertPos) + insertion + result.substring(insertPos)
        }

        // Append non-inline references at the end
        if (nonInlineRefs.isNotEmpty()) {
            result += "\n\n--- Attached Files ---"

            for (ref in nonInlineRefs) {
                result += if (ref.content != null) {
                    // Include file content if available
                    "\n\n${formatFileHeader(ref)}\n${ref.content}\n--- end ${ref.fileName} ---"
                } else {
                    // Show metadata for reference-only files
                    "\n${formatFileReference(ref)}"
                }
            }
        }

        return result
    }

    /**
     * Formats file size in bytes to a human-readable string.
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${String.format(Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0))} MB"
            else -> "${String.format(Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    /**
     * Formats an Instant to a human-readable date/time string.
     */
    private fun formatLastModified(instant: Instant): String {
        val tz = TimeZone.currentSystemDefault()
        val localDateTime = instant.toLocalDateTime(tz)
        return "${localDateTime.date} ${
            String.format(
                Locale.ROOT,
                "%02d:%02d",
                localDateTime.hour,
                localDateTime.minute
            )
        }"
    }
}
