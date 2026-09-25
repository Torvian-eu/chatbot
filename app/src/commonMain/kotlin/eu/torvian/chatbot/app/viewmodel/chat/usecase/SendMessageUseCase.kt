package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_sending_message_short
import eu.torvian.chatbot.app.generated.resources.warning_no_agent_role_selected
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.service.agent.OperatorToolExecutor
import eu.torvian.chatbot.app.service.security.RequestSigningService
import eu.torvian.chatbot.app.utils.misc.isStreamingEnabled
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.app.viewmodel.sessionstatus.SessionTurnStatusRegistry
import eu.torvian.chatbot.app.viewmodel.sessionstatus.TurnOutcome
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionAuthorization
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.*
import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Use case for sending messages in chat sessions.
 * Handles both streaming and non-streaming message sending based on settings.
 *
 * @property sessionRepository Repository responsible for chat message transport and session updates.
 * @property toolRepository Repository used to resolve tool definitions and cached approval preferences.
 * @property requestSigningService Service that signs Local MCP authorization payloads on-device.
 * @property operatorToolExecutor General operator-tool executor that runs `spawn_agent` /
 *            `send_message` requests headlessly on a second WebSocket and reports the result back
 *            on the original socket (a central router that dispatches to the per-tool operator
 *            tools).
 * @property state Shared chat UI state observed and updated during message sending.
 * @property notificationService Notification sink for repository, API, and signing errors.
 * @property sessionTurnStatusRegistry Registry that receives the turn lifecycle signals backing the
 *           session list status indicators.
 */
class SendMessageUseCase(
    private val sessionRepository: SessionRepository,
    private val toolRepository: ToolRepository,
    private val requestSigningService: RequestSigningService,
    private val operatorToolExecutor: OperatorToolExecutor,
    private val state: ChatState,
    private val notificationService: NotificationService,
    private val sessionTurnStatusRegistry: SessionTurnStatusRegistry
) {

    private val logger = kmpLogger<SendMessageUseCase>()

    /**
     * Flow for emitting follow-up client events during an active chat WebSocket session.
     *
     * This carries tool approval responses for regular tools, signed Local MCP / Built-in Worker
     * approvals, as well as turn control events such as [ChatClientEvent.Pause] and
     * [ChatClientEvent.Cancel].
     */
    private val clientEventFlow = MutableSharedFlow<ChatClientEvent>()

    /**
     * Bookkeeping for the most recently started turn on this instance, or `null` between turns.
     *
     * The tracker lives at instance level because tool-approval decisions arrive through
     * [approveToolCall] / [denyToolCall], outside [execute]'s call stack, so every started turn
     * simply replaces the slot. Nothing here enforces single-flight: when the chat view model that
     * owns this use case is re-targeted to another session while a turn is still running, only the
     * newer turn stays tracked and the older turn's deferred approvals are not reported to the
     * status registry. That overlap is a known, accepted limitation.
     */
    private val turnStatusTracking = MutableStateFlow<TurnStatusTracking?>(null)

    /**
     * Per-turn signals that decide the session status indicator for one running turn.
     *
     * @property sessionId Session the tracked turn belongs to.
     * @property pendingApprovalIds Tool calls deferred to a manual user decision; the turn is
     *           "requesting input" while this set is non-empty.
     * @property lastTerminalMessage Final assistant message delivered for the turn, if any.
     * @property turnEndedInError Whether a turn-ending error was observed before the turn ended.
     */
    private class TurnStatusTracking(val sessionId: Long) {
        val pendingApprovalIds = MutableStateFlow<Set<Long>>(emptySet())
        var lastTerminalMessage: ChatMessage.AssistantMessage? = null
        var turnEndedInError: Boolean = false

        /**
         * Records that the turn ended through a failed send or receive rather than a terminal frame.
         *
         * Such an ending carries no terminal message of its own, so it is an error ending for the
         * badge unless a completed message already arrived during the turn.
         */
        fun markEndedInError() {
            turnEndedInError = true
        }

        /**
         * Computes the completion badge this turn earns, or `null` when it earns none.
         *
         * Cancellation and user interruption are checked before message completeness so an
         * interrupted turn is never misread as a success; a turn-ending error counts as failure only
         * when no completed final message arrived, and tool errors inside a normally finishing turn
         * are ignored.
         *
         * @param wasCancelled Whether the client coroutine running the turn was cancelled.
         * @return The terminal outcome badge, or `null` for interrupted or inconclusive endings.
         */
        fun resolveOutcome(wasCancelled: Boolean): TurnOutcome? = when {
            wasCancelled -> null
            lastTerminalMessage?.incompleteCause == AssistantMessageIncompleteCause.INTERRUPTED_BY_USER -> null
            lastTerminalMessage?.incompleteCause == AssistantMessageIncompleteCause.FAILED -> TurnOutcome.FAILURE
            turnEndedInError && lastTerminalMessage?.isComplete != true -> TurnOutcome.FAILURE
            lastTerminalMessage?.isComplete == true -> TurnOutcome.SUCCESS
            else -> null
        }
    }

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
     * Requests cancellation of the currently active turn while preserving the WebSocket collector.
     *
     * The event is sent through the same shared outbound stream as tool approvals, so the server can
     * cancel processing and still deliver the cancellation finalizer's terminal events.
     */
    suspend fun requestCancellation() {
        logger.debug("Requesting cancellation of the active chat turn")
        clientEventFlow.emit(ChatClientEvent.Cancel)
    }

    /**
     * Requests a soft pause for the active turn, allowing its current assistant/tool step to finish.
     */
    suspend fun requestPause() {
        logger.debug("Requesting pause for active chat turn")
        clientEventFlow.emit(ChatClientEvent.Pause)
    }

    /**
     * Emits the typed approval event matching [toolCall]'s tool kind.
     *
     * Built-in worker and Local MCP tools are authorized with an on-device signed request; operator
     * tools are authorized with a plain [ChatClientEvent.OperatorToolCallApproval] because they are
     * executed by the operator over the chat socket, not dispatched to a worker; server built-in
     * tools are authorized with a plain [ChatClientEvent.ServerBuiltInToolCallApproval] because they
     * are executed in-process on the server.
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
        // The user has made their decision, so the call leaves the "requesting input" set before the
        // outcome is relayed, even if the relay below cannot be emitted.
        resolvePendingApproval(toolCall)
        when (val toolDefinition = findToolDefinition(toolCall)) {
            is BuiltInWorkerToolDefinition -> {
                // Built-in worker tools require an on-device cryptographic signature before execution.
                emitBuiltInApprovalEvent(
                    toolCall = toolCall,
                    toolDefinition = toolDefinition,
                    approved = approved,
                    denialReason = denialReason
                )
            }

            is OperatorToolDefinition -> {
                // Operator tools run over the chat socket, so the operator approval needs no signature.
                clientEventFlow.emit(
                    ChatClientEvent.OperatorToolCallApproval(
                        toolCallId = toolCall.id,
                        approved = approved,
                        denialReason = denialReason
                    )
                )
            }

            is ServerBuiltInToolDefinition -> {
                // Server built-in tools are executed in-process on the server, so the approval is
                // plain (no signature, no operator relay), mirroring the operator-tool UX.
                clientEventFlow.emit(
                    ChatClientEvent.ServerBuiltInToolCallApproval(
                        toolCallId = toolCall.id,
                        approved = approved,
                        denialReason = denialReason
                    )
                )
            }

            is LocalMCPToolDefinition -> {
                // Local MCP tools are dispatched to a worker and therefore require a signed authorization.
                emitLocalMcpApprovalEvent(
                    toolCall = toolCall,
                    toolDefinition = toolDefinition,
                    approved = approved,
                    denialReason = denialReason
                )
            }

            // A missing or future unsupported definition cannot be authorized safely.
            else -> {
                logger.warn(
                    "No tool definition resolved for tool call ${toolCall.id} (${toolCall.toolName}); cannot emit approval"
                )
                notificationService.genericError(
                    shortMessage = "Failed to authorize tool call",
                    detailedMessage = "No tool definition could be resolved for tool call ${toolCall.id} (${toolCall.toolName})."
                )
            }
        }
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
        clientEventFlow.emit(
            ChatClientEvent.LocalMcpToolCallApproval(
                signedRequest = signedRequest
            )
        )
    }

    /**
     * Builds, signs, and emits a built-in worker tool authorization event for one tool call.
     *
     * The typed [BuiltInToolExecutionAuthorization] is created locally and signed on-device to produce a
     * detached [SignedRequest]. Only the signed request is emitted to the server, which relays it to the
     * worker. The worker verifies the signature and decodes the authorization from the signed payload as the
     * sole source of truth for execution parameters, ensuring the client device authorized the exact call.
     *
     * @param toolCall Tool call the app is authorizing.
     * @param toolDefinition Resolved built-in worker tool definition.
     * @param approved Whether execution should proceed.
     * @param denialReason Optional denial reason supplied by the user or an auto-deny preference.
     */
    private suspend fun emitBuiltInApprovalEvent(
        toolCall: ToolCall,
        toolDefinition: BuiltInWorkerToolDefinition,
        approved: Boolean,
        denialReason: String?
    ) {
        val currentSession = state.currentSession.value
        if (currentSession == null) {
            notificationService.genericError(
                shortMessage = "Failed to authorize built-in tool call",
                detailedMessage = "No active session is available for tool call ${toolCall.id}."
            )
            return
        }

        // Build typed authorization locally for signing
        val authorization = BuiltInToolExecutionAuthorization(
            toolCallId = toolCall.id,
            sessionId = currentSession.id,
            messageId = toolCall.messageId,
            toolDefinitionId = toolDefinition.id,
            toolName = toolCall.toolName,
            workerId = toolDefinition.workerId,
            builtInToolName = toolDefinition.builtInToolName,
            input = toolCall.input,
            approved = approved,
            denialReason = denialReason
        )

        val signedRequest = requestSigningService.signRequest(
            request = authorization,
            serializer = BuiltInToolExecutionAuthorization.serializer()
        ).fold(
            ifLeft = { error ->
                notificationService.genericError(
                    shortMessage = "Failed to authorize built-in tool call",
                    detailedMessage = error.message,
                    originalThrowable = error.cause
                )
                return
            },
            ifRight = { it }
        )

        // Emit only the signed request; the typed authorization is serialized in signedRequest.payload
        clientEventFlow.emit(
            ChatClientEvent.BuiltInToolCallApproval(
                signedRequest = signedRequest
            )
        )
    }

    /**
     * Resolves the persisted definition for a tool call from the current cache or the repository.
     *
     * The caller performs the subtype dispatch because all approval paths share the same lookup
     * semantics, while the subtype determines whether signing or operator relaying is required.
     * A cached definition is authoritative for its ID; this avoids making one repository request
     * for each possible tool category.
     *
     * @param toolCall Tool call whose definition should be resolved.
     * @return Matching tool definition, or `null` when the call has no definition ID or resolution fails.
     */
    private suspend fun findToolDefinition(toolCall: ToolCall): ToolDefinition? {
        val toolDefinitionId = toolCall.toolDefinitionId ?: return null
        val cachedDefinition = toolRepository.tools.value.dataOrNull
            ?.firstOrNull { toolDefinition -> toolDefinition.id == toolDefinitionId }
        if (cachedDefinition != null) {
            return cachedDefinition
        }

        return toolRepository.getToolById(toolDefinitionId).fold(
            ifLeft = { null },
            ifRight = { it }
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
     * @return Whether the decision was deferred to the user because no stored preference could
     *         resolve it.
     */
    private suspend fun handleToolCallApprovalRequested(toolCall: ToolCall): Boolean {
        logger.debug("Tool call approval requested: ${toolCall.toolName}")

        when (val toolDefinition = findToolDefinition(toolCall)) {
            is BuiltInWorkerToolDefinition -> {
                // Built-in worker approvals must be signed locally before they are relayed to a worker.
                val preference = findApprovalPreference(toolDefinition.id) ?: return true
                val denialReason = if (preference.autoApprove) {
                    null
                } else {
                    preference.denialReason ?: "Auto-denied by user preference"
                }

                emitBuiltInApprovalEvent(
                    toolCall = toolCall,
                    toolDefinition = toolDefinition,
                    approved = preference.autoApprove,
                    denialReason = denialReason
                )
            }

            is OperatorToolDefinition -> {
                // Operator tools are relayed over the chat socket and do not need an on-device signature.
                val preference = findApprovalPreference(toolDefinition.id) ?: return true
                val denialReason = if (preference.autoApprove) {
                    null
                } else {
                    preference.denialReason ?: "Auto-denied by user preference"
                }

                clientEventFlow.emit(
                    ChatClientEvent.OperatorToolCallApproval(
                        toolCallId = toolCall.id,
                        approved = preference.autoApprove,
                        denialReason = denialReason
                    )
                )
            }

            is ServerBuiltInToolDefinition -> {
                // Server built-in tools are executed in-process on the server; the approval is plain
                // and driven by the same per-user preference store as operator tools.
                val preference = findApprovalPreference(toolDefinition.id) ?: return true
                val denialReason = if (preference.autoApprove) {
                    null
                } else {
                    preference.denialReason ?: "Auto-denied by user preference"
                }

                clientEventFlow.emit(
                    ChatClientEvent.ServerBuiltInToolCallApproval(
                        toolCallId = toolCall.id,
                        approved = preference.autoApprove,
                        denialReason = denialReason
                    )
                )
            }

            is LocalMCPToolDefinition -> {
                val preference = findApprovalPreference(toolDefinition.id) ?: return true
                val denialReason = if (preference.autoApprove) {
                    null
                } else {
                    preference.denialReason ?: "Auto-denied by user preference"
                }

                emitLocalMcpApprovalEvent(
                    toolCall = toolCall,
                    toolDefinition = toolDefinition,
                    approved = preference.autoApprove,
                    denialReason = denialReason
                )
            }

            // Unknown or unresolved definitions cannot be auto-approved safely.
            else -> return true
        }

        // A stored preference decided the call instantly, so the user is never asked.
        return false
    }

    /**
     * Records a tool call deferred to a manual user decision and flags the session as awaiting input.
     *
     * @param sessionId Session whose turn deferred the decision.
     * @param toolCallId Tool call left for the user to decide.
     */
    private fun noteApprovalDeferred(sessionId: Long, toolCallId: Long) {
        val tracking = turnStatusTracking.value?.takeIf { it.sessionId == sessionId } ?: return
        tracking.pendingApprovalIds.update { it + toolCallId }
        sessionTurnStatusRegistry.onTurnAwaitingInput(sessionId, true)
    }

    /**
     * Resolves one pending manual decision and refreshes the session's awaiting-input state.
     *
     * @param toolCall Tool call the user just approved or denied.
     */
    private fun resolvePendingApproval(toolCall: ToolCall) {
        val tracking = turnStatusTracking.value ?: return
        tracking.pendingApprovalIds.update { it - toolCall.id }
        sessionTurnStatusRegistry.onTurnAwaitingInput(
            tracking.sessionId,
            tracking.pendingApprovalIds.value.isNotEmpty()
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

        // Check that the session has a resolvable agent role. The role bundles model and settings;
        // a missing role (nothing selected or role deleted server-side) or a broken role (referenced
        // model/settings deleted) makes the session inert until the role is repaired or re-selected.
        val currentRole = state.currentAgentRole.value
        val currentModel = state.currentModel.value
        val currentSettings = state.currentSettings.value

        if (currentRole == null || currentModel == null || currentSettings == null) {
            notificationService.genericWarning(
                shortMessageRes = Res.string.warning_no_agent_role_selected,
                detailedMessage = "Role: ${currentRole?.name ?: "none"}, Model: ${currentModel?.name ?: "not available"}, Settings: ${if (currentSettings != null) "available" else "not available"}"
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

        // Check if streaming is enabled in settings
        val isStreamingEnabled = currentSettings.isStreamingEnabled()

        val request = ProcessNewMessageRequest(
            content = content,
            parentMessageId = parentId,
            isStreaming = isStreamingEnabled,
            fileReferences = fileReferences
        )

        // The turn spans the whole tool loop; its status is tracked from here to the terminal event.
        val turnTracking = TurnStatusTracking(currentSession.id)
        turnStatusTracking.value = turnTracking
        sessionTurnStatusRegistry.onTurnStarted(currentSession.id)
        try {
            if (isStreamingEnabled) {
                handleStreamingMessage(currentSession.id, request, turnTracking)
            } else {
                handleNonStreamingMessage(currentSession.id, request, turnTracking)
            }
        } finally {
            // Runs for every ending of the turn, including cancellation; the registry calls are plain
            // state writes and therefore safe in a cancelled coroutine.
            val wasCancelled = !currentCoroutineContext().isActive
            sessionTurnStatusRegistry.onTurnFinished(
                currentSession.id,
                turnTracking.resolveOutcome(wasCancelled)
            )
            turnStatusTracking.compareAndSet(turnTracking, null)
        }
    }

    /**
     * Handles streaming message processing using SessionRepository.
     * This function orchestrates the bidirectional flow of events for the WebSocket connection.
     *
     * @param sessionId Session the turn belongs to.
     * @param request New-message request that starts the turn.
     * @param turnTracking Per-turn bookkeeping updated with the turn's terminal signals.
     */
    private suspend fun handleStreamingMessage(
        sessionId: Long,
        request: ProcessNewMessageRequest,
        turnTracking: TurnStatusTracking
    ) {
        // Create the main client-to-server event flow by merging the initial message request
        // with any approval responses produced by the UI.
        val messageEvents: Flow<ChatClientEvent> = flowOf(ChatClientEvent.ProcessNewMessage(request))
        val clientEvents: Flow<ChatClientEvent> = merge(
            messageEvents,
            clientEventFlow
        )

        // Tracks the assistant placeholder of the current turn: set when a message starts streaming and
        // cleared when its finalizing event arrives. If it is still set when the turn ends because this
        // client cancelled it, the message's persisted state can no longer be delivered (the socket that
        // carried the turn is gone), so the placeholder is settled locally instead.
        var unfinalizedAssistantMessageId: Long? = null

        // Call the repository with the combined event flow and collect server responses. The collector
        // runs inside a coroutine scope so the spawned executor coroutine (which owns the second
        // WebSocket for operator tools) is cancelled when the primary socket closes or the turn ends.
        coroutineScope {
            try {
                sessionRepository.processNewMessageStreaming(sessionId, clientEvents).collect { eitherUpdate ->
                    eitherUpdate.fold(
                        ifLeft = { repositoryError ->
                            // A failed transport prevents the terminal frame from arriving, so the
                            // ending is recorded as an error end.
                            turnTracking.markEndedInError()
                            logger.error("Streaming message repository error: ${repositoryError.message}")
                            notificationService.repositoryError(
                                error = repositoryError,
                                shortMessageRes = Res.string.error_sending_message_short
                            )
                        },
                        ifRight = { chatUpdate ->
                            // Handle specific events that require UI state updates or further action.
                            when (chatUpdate) {
                                is ChatStreamEvent.AssistantMessageStart -> {
                                    // A new assistant message is now streaming; it is finalized either by a
                                    // terminal end event or, if this client stops the turn, by the local
                                    // settlement below.
                                    unfinalizedAssistantMessageId = chatUpdate.assistantMessage.id
                                }

                                is ChatStreamEvent.AssistantMessageEnd -> {
                                    // Terminal state delivered live, so there is nothing left to settle.
                                    unfinalizedAssistantMessageId = null
                                    turnTracking.lastTerminalMessage = chatUpdate.assistantMessage
                                }

                                is ChatStreamEvent.UserMessageSaved -> {
                                    // Clear input, reply target, and file references after user message is confirmed.
                                    state.setInputContent("")
                                    state.setReplyTarget(null)
                                    state.updateFileReferences { emptyList() }
                                }

                                is ChatStreamEvent.ToolCallApprovalRequested -> {
                                    if (handleToolCallApprovalRequested(chatUpdate.toolCall)) {
                                        noteApprovalDeferred(sessionId, chatUpdate.toolCall.id)
                                    }
                                }

                                is ChatStreamEvent.OperatorToolExecutionRequested -> {
                                    this@coroutineScope.launch {
                                        operatorToolExecutor.execute(
                                            toolCallId = chatUpdate.toolCallId,
                                            toolName = chatUpdate.toolName,
                                            payload = chatUpdate.payload,
                                            clientEvents = { result -> clientEventFlow.emit(result) }
                                        )
                                    }
                                }

                                is ChatStreamEvent.ErrorOccurred -> {
                                    turnTracking.turnEndedInError = true
                                    notificationService.apiError(
                                        error = chatUpdate.error,
                                        shortMessageRes = Res.string.error_sending_message_short
                                    )
                                }

                                is ChatStreamEvent.CompactionCompleted -> {
                                    // FR-13: display a subtle informational notice. The event never
                                    // creates/replaces transcript messages; the repository also keeps the
                                    // session cache untouched.
                                    notificationService.genericSuccess(
                                        "Conversation compacted: ${chatUpdate.payload.coveredMessageIds.size} " +
                                            "messages summarized (${chatUpdate.payload.sourceTokenCount} → " +
                                            "${chatUpdate.payload.resultTokenCount} tokens)"
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
            } finally {
                // Runs for every ending of the turn, including cancellation. A placeholder left unfinalized and
                // a send coroutine that was cancelled mean this client tore the socket down itself: the server
                // cannot deliver the terminal frame any more, but the cause of that ending is known (`the user
                // stopped it`), so the cached message is settled locally rather than fetched.
                val stoppedMessageId = unfinalizedAssistantMessageId
                if (stoppedMessageId != null && !currentCoroutineContext().isActive) {
                    markStoppedTurnLocally(sessionId, stoppedMessageId)
                }
            }
        }
    }

    /**
     * Handles non-streaming message processing using SessionRepository.
     * This function orchestrates the bidirectional flow of events for the WebSocket connection.
     *
     * @param sessionId Session the turn belongs to.
     * @param request New-message request that starts the turn.
     * @param turnTracking Per-turn bookkeeping updated with the turn's terminal signals.
     */
    private suspend fun handleNonStreamingMessage(
        sessionId: Long,
        request: ProcessNewMessageRequest,
        turnTracking: TurnStatusTracking
    ) {
        // Create the main client-to-server event flow by merging the initial message request
        // with any approval responses produced by the UI.
        val messageEvents: Flow<ChatClientEvent> = flowOf(ChatClientEvent.ProcessNewMessage(request))
        val clientEvents: Flow<ChatClientEvent> = merge(
            messageEvents,
            clientEventFlow
        )

        // Call the repository with the combined event flow and collect server responses. The collector
        // runs inside a coroutine scope so the spawned executor coroutine (which owns the second
        // WebSocket for operator tools) is cancelled when the primary socket closes or the turn ends.
        coroutineScope {
            sessionRepository.processNewMessage(sessionId, clientEvents).collect { eitherEvent ->
                eitherEvent.fold(
                    ifLeft = { repositoryError ->
                        // A failed transport prevents the terminal frame from arriving, so the
                        // ending is recorded as an error end.
                        turnTracking.markEndedInError()
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

                            is ChatEvent.AssistantMessageSaved -> {
                                turnTracking.lastTerminalMessage = event.assistantMessage
                            }

                            is ChatEvent.ToolCallApprovalRequested -> {
                                if (handleToolCallApprovalRequested(event.toolCall)) {
                                    noteApprovalDeferred(sessionId, event.toolCall.id)
                                }
                            }

                            is ChatEvent.OperatorToolExecutionRequested -> {
                                this@coroutineScope.launch {
                                    operatorToolExecutor.execute(
                                        toolCallId = event.toolCallId,
                                        toolName = event.toolName,
                                        payload = event.payload,
                                        clientEvents = { result -> clientEventFlow.emit(result) }
                                    )
                                }
                            }

                            is ChatEvent.ErrorOccurred -> {
                                turnTracking.turnEndedInError = true
                                notificationService.apiError(
                                    error = event.error,
                                    shortMessageRes = Res.string.error_sending_message_short
                                )
                            }

                            is ChatEvent.CompactionCompleted -> {
                                // FR-13: display a subtle informational notice. The event never
                                // creates/replaces transcript messages; the repository also keeps the
                                // session cache untouched.
                                notificationService.genericSuccess(
                                    "Conversation compacted: ${event.payload.coveredMessageIds.size} " +
                                        "messages summarized (${event.payload.sourceTokenCount} → " +
                                        "${event.payload.resultTokenCount} tokens)"
                                )
                            }

                            else -> {
                                // Other events (e.g., StreamCompleted) are handled
                                // by the repository, which updates the UI state reactively.
                            }
                        }
                    }
                )
            }
        }
    }

    /**
     * Settles the assistant message of a turn this client stopped itself, without any session request.
     *
     * The terminal event of a torn-down turn cannot be delivered (the socket that carried it is cancelled on
     * the server side as well), and the case is the one ending whose cause the client knows for sure: it was the
     * user's own stop, which the server persists as `INTERRUPTED_BY_USER`. Settling the message the client
     * already holds keeps the notice immediate (D5/FR-16) while leaving the persisted state authoritative — the
     * next session load overwrites the cached copy, and a message that already reached a terminal state is not
     * touched by the repository guard, so a delivered terminal event always wins.
     *
     * @param sessionId Session whose cached message is settled.
     * @param messageId Assistant message left unfinalized by the cancelled turn.
     */
    private suspend fun markStoppedTurnLocally(sessionId: Long, messageId: Long) {
        // `NonCancellable` is required because the repository's cache update takes a mutex: the cancelled send
        // coroutine would abort at that suspension point, which is exactly the case this settlement exists for.
        // No I/O happens inside the block.
        withContext(NonCancellable) {
            sessionRepository.markAssistantMessageInterrupted(sessionId = sessionId, messageId = messageId)
        }
    }
}
