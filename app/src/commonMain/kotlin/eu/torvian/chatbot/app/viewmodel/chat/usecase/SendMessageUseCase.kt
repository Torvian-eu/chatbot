package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.domain.TurnOutcome
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.warning_no_agent_role_selected
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.service.agent.OperatorToolExecutor
import eu.torvian.chatbot.app.service.security.RequestSigningService
import eu.torvian.chatbot.app.utils.misc.isStreamingEnabled
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.usecase.approval.ToolApprovalEventEmitter
import eu.torvian.chatbot.app.viewmodel.chat.usecase.turn.NonStreamingTurnCollector
import eu.torvian.chatbot.app.viewmodel.chat.usecase.turn.StreamingTurnCollector
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.app.viewmodel.sessionstatus.SessionTurnStatusRegistry
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.core.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive

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
 *           session list status indicators and the out-of-app turn alerts.
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

    /** Emitter for the typed tool approval events, sharing this use case's turn bookkeeping. */
    private val approvalEventEmitter = ToolApprovalEventEmitter(
        toolRepository = toolRepository,
        requestSigningService = requestSigningService,
        state = state,
        clientEventFlow = clientEventFlow,
        notificationService = notificationService,
        resolvePendingApproval = ::resolvePendingApproval
    )

    /** Collector that consumes a streaming turn's server event stream and updates chat state. */
    private val streamingTurnCollector = StreamingTurnCollector(
        sessionRepository = sessionRepository,
        notificationService = notificationService,
        operatorToolExecutor = operatorToolExecutor,
        state = state,
        clientEventFlow = clientEventFlow,
        handleToolCallApprovalRequested = ::handleToolCallApprovalRequested,
        noteApprovalDeferred = ::noteApprovalDeferred
    )

    /** Collector that consumes a non-streaming turn's server event stream and updates chat state. */
    private val nonStreamingTurnCollector = NonStreamingTurnCollector(
        sessionRepository = sessionRepository,
        notificationService = notificationService,
        operatorToolExecutor = operatorToolExecutor,
        state = state,
        clientEventFlow = clientEventFlow,
        handleToolCallApprovalRequested = ::handleToolCallApprovalRequested,
        noteApprovalDeferred = ::noteApprovalDeferred
    )

    /**
     * Per-turn signals that decide the session status indicator for one running turn.
     *
     * @property sessionId Session the tracked turn belongs to.
     * @property pendingApprovalIds Tool calls deferred to a manual user decision; the turn is
     *           "requesting input" while this set is non-empty.
     * @property lastTerminalMessage Final assistant message delivered for the turn, if any.
     * @property turnEndedInError Whether a turn-ending error was observed before the turn ended.
     */
    internal class TurnStatusTracking(val sessionId: Long) {
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
        approvalEventEmitter.emitApprovalEvent(toolCall = toolCall, approved = true, denialReason = null)
    }

    /**
     * Denies a tool call and prevents it from executing.
     *
     * @param toolCall Tool call to deny.
     * @param reason Optional reason for denying the tool call.
     */
    suspend fun denyToolCall(toolCall: ToolCall, reason: String?) {
        logger.debug("Denying tool call: ${toolCall.id}, reason: $reason")
        approvalEventEmitter.emitApprovalEvent(toolCall = toolCall, approved = false, denialReason = reason)
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

        when (val toolDefinition = approvalEventEmitter.findToolDefinition(toolCall)) {
            is BuiltInWorkerToolDefinition -> {
                // Built-in worker approvals must be signed locally before they are relayed to a worker.
                val preference = approvalEventEmitter.findApprovalPreference(toolDefinition.id) ?: return true
                val denialReason = if (preference.autoApprove) {
                    null
                } else {
                    preference.denialReason ?: "Auto-denied by user preference"
                }

                approvalEventEmitter.emitBuiltInApprovalEvent(
                    toolCall = toolCall,
                    toolDefinition = toolDefinition,
                    approved = preference.autoApprove,
                    denialReason = denialReason
                )
            }

            is OperatorToolDefinition -> {
                // Operator tools are relayed over the chat socket and do not need an on-device signature.
                val preference = approvalEventEmitter.findApprovalPreference(toolDefinition.id) ?: return true
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
                val preference = approvalEventEmitter.findApprovalPreference(toolDefinition.id) ?: return true
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
                val preference = approvalEventEmitter.findApprovalPreference(toolDefinition.id) ?: return true
                val denialReason = if (preference.autoApprove) {
                    null
                } else {
                    preference.denialReason ?: "Auto-denied by user preference"
                }

                approvalEventEmitter.emitLocalMcpApprovalEvent(
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
                streamingTurnCollector.handleStreamingMessage(currentSession.id, request, turnTracking)
            } else {
                nonStreamingTurnCollector.handleNonStreamingMessage(currentSession.id, request, turnTracking)
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
}
