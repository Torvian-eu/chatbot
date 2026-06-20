package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_sending_message_short
import eu.torvian.chatbot.app.generated.resources.warning_model_or_settings_unavailable
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.service.security.RequestSigningService
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.tool.ToolCallApprovalResponse
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge

/**
 * Use case for sending messages in chat sessions.
 * Handles both streaming and non-streaming message sending based on settings.
 *
 * @property sessionRepository Repository responsible for chat message transport and session updates.
 * @property toolRepository Repository used to resolve tool definitions and cached approval preferences.
 * @property requestSigningService Service that signs Local MCP authorization payloads on-device.
 * @property state Shared chat UI state observed and updated during message sending.
 * @property notificationService Notification sink for repository, API, and signing errors.
 */
class SendMessageUseCase(
    private val sessionRepository: SessionRepository,
    private val toolRepository: ToolRepository,
    private val requestSigningService: RequestSigningService,
    private val state: ChatState,
    private val notificationService: NotificationService
) {

    private val logger = kmpLogger<SendMessageUseCase>()

    /**
     * Flow for emitting follow-up client events during an active chat WebSocket session.
     *
     * This carries either plain approval responses for regular tools or signed Local MCP approvals that
     * the server can forward to the worker.
     */
    private val toolApprovalFlow = MutableSharedFlow<ChatClientEvent>()

    /**
     * Approves a tool call and allows it to execute.
     *
     * Local MCP tool approvals are signed on-device before being emitted so the worker can verify them.
     *
     * @param toolCall Tool call to approve.
     */
    suspend fun approveToolCall(toolCall: ToolCall) {
        logger.debug("Approving tool call: ${toolCall.id}")
        emitApprovalEvent(toolCall = toolCall, approved = true, denialReason = null)
    }

    /**
     * Denies a tool call and prevents it from executing.
     *
     * @param toolCall Tool call to deny.
     * @param reason Optional reason for denying the tool call.
     */
    suspend fun denyToolCall(toolCall: ToolCall, reason: String?) {
        logger.debug("Denying tool call: ${toolCall.id}, reason: $reason")
        emitApprovalEvent(toolCall = toolCall, approved = false, denialReason = reason)
    }

    /**
     * Emits either a plain approval response or a signed Local MCP approval event for [toolCall].
     *
     * @param toolCall Tool call the user approved or denied.
     * @param approved Whether execution should proceed.
     * @param denialReason Optional denial reason supplied by the user or an auto-deny preference.
     */
    private suspend fun emitApprovalEvent(
        toolCall: ToolCall,
        approved: Boolean,
        denialReason: String?
    ) {
        val localMcpTool = findLocalMcpToolDefinition(toolCall)
        if (localMcpTool == null) {
            toolApprovalFlow.emit(
                ChatClientEvent.ToolCallApproval(
                    ToolCallApprovalResponse(
                        toolCallId = toolCall.id,
                        approved = approved,
                        denialReason = denialReason
                    )
                )
            )
            return
        }

        emitLocalMcpApprovalEvent(
            toolCall = toolCall,
            toolDefinition = localMcpTool,
            approved = approved,
            denialReason = denialReason
        )
    }

    /**
     * Builds, signs, and emits a Local MCP authorization event for one tool call.
     *
     * The typed [LocalMCPToolExecutionAuthorization] is created locally and signed to produce
     * a detached [SignedRequest]. Only the signed request is emitted to the server, which relays it
     * to the worker. The worker verifies the signature and decodes the authorization from
     * the signed payload as the sole source of truth for execution parameters.
     *
     * @param toolCall Tool call the app is authorizing.
     * @param toolDefinition Resolved Local MCP tool definition.
     * @param approved Whether execution should proceed.
     * @param denialReason Optional denial reason supplied by the user or an auto-deny preference.
     */
    private suspend fun emitLocalMcpApprovalEvent(
        toolCall: ToolCall,
        toolDefinition: LocalMCPToolDefinition,
        approved: Boolean,
        denialReason: String?
    ) {
        val currentSession = state.currentSession.value
        if (currentSession == null) {
            notificationService.genericError(
                shortMessage = "Failed to authorize Local MCP tool call",
                detailedMessage = "No active session is available for tool call ${toolCall.id}."
            )
            return
        }

        // Build typed authorization locally for signing
        val authorization = LocalMCPToolExecutionAuthorization(
            toolCallId = toolCall.id,
            sessionId = currentSession.id,
            messageId = toolCall.messageId,
            toolDefinitionId = toolDefinition.id,
            toolName = toolCall.toolName,
            serverId = toolDefinition.serverId,
            mcpToolName = toolDefinition.mcpToolName,
            input = toolCall.input,
            approved = approved,
            denialReason = denialReason
        )

        val signedRequest = requestSigningService.signRequest(
            request = authorization,
            serializer = LocalMCPToolExecutionAuthorization.serializer()
        ).fold(
            ifLeft = { error ->
                notificationService.genericError(
                    shortMessage = "Failed to authorize Local MCP tool call",
                    detailedMessage = error.message,
                    originalThrowable = error.cause
                )
                return
            },
            ifRight = { it }
        )

        // Emit only the signed request; the typed authorization is serialized in signedRequest.payload
        toolApprovalFlow.emit(
            ChatClientEvent.LocalMcpToolCallApproval(
                signedRequest = signedRequest
            )
        )
    }

    /**
     * Resolves the Local MCP tool definition for [toolCall] when the current tool cache contains one.
     *
     * @param toolCall Tool call whose definition should be resolved.
     * @return Matching [LocalMCPToolDefinition] or `null` when the tool is not Local MCP or the cache lacks it.
     */
    private suspend fun findLocalMcpToolDefinition(toolCall: ToolCall): LocalMCPToolDefinition? {
        val toolDefinitionId = toolCall.toolDefinitionId ?: return null
        val cachedDefinition = toolRepository.tools.value.dataOrNull
            ?.firstOrNull { toolDefinition -> toolDefinition.id == toolDefinitionId } as? LocalMCPToolDefinition
        if (cachedDefinition != null) {
            return cachedDefinition
        }

        return toolRepository.getToolById(toolDefinitionId).fold(
            ifLeft = { null },
            ifRight = { it as? LocalMCPToolDefinition }
        )
    }

    /**
     * Returns the cached approval preference for [toolDefinitionId], if one is currently loaded.
     *
     * @param toolDefinitionId Tool definition whose preference should be inspected.
     * @return Matching approval preference or `null` when no preference is cached.
     */
    private fun findApprovalPreference(toolDefinitionId: Long): UserToolApprovalPreference? {
        return toolRepository.toolApprovalPreferences.value.dataOrNull
            ?.firstOrNull { preference -> preference.toolDefinitionId == toolDefinitionId }
    }

    /**
     * Reacts to a server approval request and auto-signs Local MCP decisions when the app already has a user
     * preference cached for the referenced tool.
     *
     * Non-Local-MCP auto-approval continues to be handled on the server, so this helper only participates in
     * the Local MCP trust chain.
     *
     * @param toolCall Tool call now awaiting approval on the server.
     */
    private suspend fun handleToolCallApprovalRequested(toolCall: ToolCall) {
        logger.debug("Tool call approval requested: ${toolCall.toolName}")

        val localMcpTool = findLocalMcpToolDefinition(toolCall) ?: return
        val preference = findApprovalPreference(localMcpTool.id) ?: return
        val denialReason = if (preference.autoApprove) {
            null
        } else {
            preference.denialReason ?: "Auto-denied by user preference"
        }

        emitLocalMcpApprovalEvent(
            toolCall = toolCall,
            toolDefinition = localMcpTool,
            approved = preference.autoApprove,
            denialReason = denialReason
        )
    }

    /**
     * Sends the current message content to the active session, or continues from a specific message.
     *
     * @param continueFromMessage When provided, uses Branch & Continue mode: sends null content
     *                            with this message's ID as parentMessageId to continue the conversation
     *                            from that point. When null, sends the current input content normally.
     */
    suspend fun execute(continueFromMessage: ChatMessage? = null) {
        val currentSession = state.currentSession.value ?: return

        // Check if model or model settings are available
        val currentModel = state.currentModel.value
        val currentSettings = state.currentSettings.value

        if (currentModel == null || currentSettings == null) {
            notificationService.genericWarning(
                shortMessageRes = Res.string.warning_model_or_settings_unavailable,
                detailedMessage = "Model: ${currentModel?.name ?: "not available"}, Settings: ${if (currentSettings != null) "available" else "not available"}"
            )
            return
        }

        // Determine content and parent based on mode
        val (content, parentId, fileReferences) = if (continueFromMessage != null) {
            // Branch & Continue mode: null content, use specified message as parent
            logger.info("Branch & Continue from message ${continueFromMessage.id} in session ${currentSession.id}")
            Triple(null, continueFromMessage.id, emptyList())
        } else {
            // Regular mode: use input content and determine parent from reply target or current leaf
            val inputContent = state.inputContent.value.trim()
            if (inputContent.isBlank()) return // Cannot send empty message

            val parent = state.replyTargetMessage.value?.id ?: currentSession.currentLeafMessageId
            val pendingRefs = state.pendingFileReferences.value

            logger.info("Sending message to session ${currentSession.id}, parent: $parent, fileRefs: ${pendingRefs.size}")
            Triple(inputContent, parent, pendingRefs)
        }

        state.setIsSending(true) // Set sending state to true

        try {
            // Check if streaming is enabled in settings
            val isStreamingEnabled = currentSettings.stream

            val request = ProcessNewMessageRequest(
                content = content,
                parentMessageId = parentId,
                isStreaming = isStreamingEnabled,
                fileReferences = fileReferences
            )

            if (isStreamingEnabled) {
                handleStreamingMessage(currentSession.id, request)
            } else {
                handleNonStreamingMessage(currentSession.id, request)
            }
        } finally {
            state.setIsSending(false) // Always reset sending state
        }
    }

    /**
     * Handles streaming message processing using SessionRepository.
     * This function orchestrates the bidirectional flow of events for the WebSocket connection.
     */
    private suspend fun handleStreamingMessage(
        sessionId: Long,
        request: ProcessNewMessageRequest
    ) {
        // Create the main client-to-server event flow by merging the initial message request
        // with any approval responses produced by the UI.
        val messageEvents: Flow<ChatClientEvent> = flowOf(ChatClientEvent.ProcessNewMessage(request))
        val clientEvents: Flow<ChatClientEvent> = merge(
            messageEvents,
            toolApprovalFlow
        )

        // Call the repository with the combined event flow and collect server responses.
        sessionRepository.processNewMessageStreaming(sessionId, clientEvents).collect { eitherUpdate ->
            eitherUpdate.fold(
                ifLeft = { repositoryError ->
                    logger.error("Streaming message repository error: ${repositoryError.message}")
                    notificationService.repositoryError(
                        error = repositoryError,
                        shortMessageRes = Res.string.error_sending_message_short
                    )
                },
                ifRight = { chatUpdate ->
                    // Handle specific events that require UI state updates or further action.
                    when (chatUpdate) {
                        is ChatStreamEvent.UserMessageSaved -> {
                            // Clear input, reply target, and file references after user message is confirmed.
                            state.setInputContent("")
                            state.setReplyTarget(null)
                            state.updateFileReferences { emptyList() }
                        }

                        is ChatStreamEvent.ToolCallApprovalRequested -> {
                            handleToolCallApprovalRequested(chatUpdate.toolCall)
                        }

                        is ChatStreamEvent.ErrorOccurred -> {
                            notificationService.apiError(
                                error = chatUpdate.error,
                                shortMessageRes = Res.string.error_sending_message_short
                            )
                        }

                        else -> {
                            // Other events (e.g., delta, tool completed) are handled by the repository's
                            // applyStreamEvent method, which updates the UI state reactively.
                        }
                    }
                }
            )
        }
    }

    /**
     * Handles non-streaming message processing using SessionRepository.
     * This function orchestrates the bidirectional flow of events for the WebSocket connection.
     */
    private suspend fun handleNonStreamingMessage(
        sessionId: Long,
        request: ProcessNewMessageRequest
    ) {
        // Create the main client-to-server event flow by merging the initial message request
        // with any approval responses produced by the UI.
        val messageEvents: Flow<ChatClientEvent> = flowOf(ChatClientEvent.ProcessNewMessage(request))
        val clientEvents: Flow<ChatClientEvent> = merge(
            messageEvents,
            toolApprovalFlow
        )

        // Call the repository with the combined event flow and collect server responses.
        sessionRepository.processNewMessage(sessionId, clientEvents).collect { eitherEvent ->
            eitherEvent.fold(
                ifLeft = { repositoryError ->
                    logger.error("Non-streaming message repository error: ${repositoryError.message}")
                    notificationService.repositoryError(
                        error = repositoryError,
                        shortMessageRes = Res.string.error_sending_message_short
                    )
                },
                ifRight = { event ->
                    // Handle specific events that require UI state updates or further action.
                    when (event) {
                        is ChatEvent.UserMessageSaved -> {
                            // Clear input, reply target, and file references after user message is confirmed.
                            state.setInputContent("")
                            state.setReplyTarget(null)
                            state.updateFileReferences { emptyList() }
                        }


                        is ChatEvent.ToolCallApprovalRequested -> {
                            handleToolCallApprovalRequested(event.toolCall)
                        }

                        is ChatEvent.ErrorOccurred -> {
                            notificationService.apiError(
                                error = event.error,
                                shortMessageRes = Res.string.error_sending_message_short
                            )
                        }

                        else -> {
                            // Other events (e.g., AssistantMessageSaved, StreamCompleted) are handled
                            // by the repository, which updates the UI state reactively.
                        }
                    }
                }
            )
        }
    }
}
