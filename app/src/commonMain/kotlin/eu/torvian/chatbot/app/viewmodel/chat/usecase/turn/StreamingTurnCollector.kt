package eu.torvian.chatbot.app.viewmodel.chat.usecase.turn

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_sending_message_short
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.service.agent.OperatorToolExecutor
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.usecase.CompactionNotifications
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SendMessageUseCase
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.tool.ToolCall
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Collects one streaming turn and settles the assistant message this client stopped itself.
 *
 * @property sessionRepository Repository responsible for chat message transport and session updates.
 * @property notificationService Notification sink for transport and API errors.
 * @property operatorToolExecutor Executor that runs operator tool calls on a second socket.
 * @property state Shared chat UI state updated while the turn streams.
 * @property clientEventFlow Shared outbound stream carrying approvals, pauses and cancels.
 * @property handleToolCallApprovalRequested Auto-answers an approval request from a stored preference and
 *           reports whether the decision was deferred to the user.
 * @property noteApprovalDeferred Reports a tool call left for a manual user decision.
 */
internal class StreamingTurnCollector(
    private val sessionRepository: SessionRepository,
    private val notificationService: NotificationService,
    private val operatorToolExecutor: OperatorToolExecutor,
    private val state: ChatState,
    private val clientEventFlow: MutableSharedFlow<ChatClientEvent>,
    private val handleToolCallApprovalRequested: suspend (ToolCall) -> Boolean,
    private val noteApprovalDeferred: (Long, Long) -> Unit
) {

    /** Diagnostics logger for streaming-turn transport failures. */
    private val logger = kmpLogger<StreamingTurnCollector>()

    /**
     * Handles streaming message processing using SessionRepository.
     * This function orchestrates the bidirectional flow of events for the WebSocket connection.
     *
     * @param sessionId Session the turn belongs to.
     * @param request New-message request that starts the turn.
     * @param turnTracking Per-turn bookkeeping updated with the turn's terminal signals.
     */
    internal suspend fun handleStreamingMessage(
        sessionId: Long,
        request: ProcessNewMessageRequest,
        turnTracking: SendMessageUseCase.TurnStatusTracking
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
                                    // Display a subtle informational notice. The event never
                                    // creates/replaces transcript messages; the repository also keeps the
                                    // session cache untouched.
                                    notificationService.genericSuccess(
                                        CompactionNotifications.successText(chatUpdate.payload)
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
     * Settles the assistant message of a turn this client stopped itself, without any session request.
     *
     * The terminal event of a torn-down turn cannot be delivered (the socket that carried it is cancelled on
     * the server side as well), and the case is the one ending whose cause the client knows for sure: it was the
     * user's own stop, which the server persists as `INTERRUPTED_BY_USER`. Settling the message the client
     * already holds keeps the notice immediate while leaving the persisted state authoritative — the
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
