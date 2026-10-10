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
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.tool.ToolCall
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Collects one non-streaming turn.
 *
 * @property sessionRepository Repository responsible for chat message transport and session updates.
 * @property notificationService Notification sink for transport and API errors.
 * @property operatorToolExecutor Executor that runs operator tool calls on a second socket.
 * @property state Shared chat UI state updated while the turn runs.
 * @property clientEventFlow Shared outbound stream carrying approvals, pauses and cancels.
 * @property handleToolCallApprovalRequested Auto-answers an approval request from a stored preference and
 *           reports whether the decision was deferred to the user.
 * @property noteApprovalDeferred Reports a tool call left for a manual user decision.
 */
internal class NonStreamingTurnCollector(
    private val sessionRepository: SessionRepository,
    private val notificationService: NotificationService,
    private val operatorToolExecutor: OperatorToolExecutor,
    private val state: ChatState,
    private val clientEventFlow: MutableSharedFlow<ChatClientEvent>,
    private val handleToolCallApprovalRequested: suspend (ToolCall) -> Boolean,
    private val noteApprovalDeferred: (Long, Long) -> Unit
) {

    /** Diagnostics logger for non-streaming-turn transport failures. */
    private val logger = kmpLogger<NonStreamingTurnCollector>()

    /**
     * Handles non-streaming message processing using SessionRepository.
     * This function orchestrates the bidirectional flow of events for the WebSocket connection.
     *
     * @param sessionId Session the turn belongs to.
     * @param request New-message request that starts the turn.
     * @param turnTracking Per-turn bookkeeping updated with the turn's terminal signals.
     */
    internal suspend fun handleNonStreamingMessage(
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
                                // Display a subtle informational notice. The event never
                                // creates/replaces transcript messages; the repository also keeps the
                                // session cache untouched.
                                notificationService.genericSuccess(
                                    CompactionNotifications.successText(event.payload)
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
}
