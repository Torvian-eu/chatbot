package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.core.CompactionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the user-requested conversation-compaction socket: its job, its lifecycle state, and the user
 * feedback for its outcome.
 *
 * The operation belongs to the session, not to the screen: the job runs in the caller-supplied scope
 * (the session's view-model scope), so navigating inside the app leaves it running and its outcome
 * reaches the global notification surface. Only cancelling the job — the composer's stop action — or
 * the app going away closes the socket.
 *
 * @property sessionRepository Repository exposing the compaction socket of a session.
 * @property state Chat state whose [ChatState.setTurnExecutionState] carries the progress indicator.
 * @property notificationService Notification sink for the operation's outcome.
 * @property scope Scope owning the running compaction; cancelling it aborts the operation.
 */
class CompactConversationUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val notificationService: NotificationService,
    private val scope: CoroutineScope
) {

    private val logger = kmpLogger<CompactConversationUseCase>()

    /** The running compaction job, or null before the first start. */
    private var job: Job? = null

    /**
     * Starts a compaction of the given session's displayed thread.
     *
     * A start while a compaction is already running is ignored, which makes the manual entry point safe
     * against a double click even before the UI reflects the new state.
     *
     * @param sessionId Session whose displayed thread is compacted.
     */
    fun start(sessionId: Long) {
        if (job?.isActive == true) return

        state.setTurnExecutionState(TurnExecutionState.COMPACTING)
        job = scope.launch {
            try {
                sessionRepository.compactConversation(sessionId).collect { outcome ->
                    outcome.fold(
                        ifLeft = { error ->
                            notificationService.repositoryError(
                                error = error,
                                shortMessage = "Failed to compact the conversation"
                            )
                        },
                        ifRight = { event -> handleEvent(event) }
                    )
                }
            } catch (cancellation: CancellationException) {
                logger.info("Conversation compaction for session $sessionId was cancelled")
                // The user's stop is the only cancellation in normal use, so it is reported; the
                // notification must run outside the cancelled job to be emitted at all.
                withContext(NonCancellable) {
                    notificationService.genericWarning(CompactionNotifications.CANCELLED_TEXT)
                }
                throw cancellation
            } finally {
                // Clearing the indicator is not suspendable, so it also runs when the job is cancelled.
                job = null
                state.setTurnExecutionState(TurnExecutionState.IDLE)
            }
        }
    }

    /**
     * Cancels the running compaction, if any.
     *
     * Closing the socket aborts the server-side auxiliary call and persists nothing; a chunk that was
     * already committed stays committed.
     */
    fun cancel() {
        job?.cancel()
    }

    /**
     * Reports one server event of the compaction socket.
     *
     * @param event Event received from the server.
     */
    private suspend fun handleEvent(event: CompactionEvent) {
        when (event) {
            is CompactionEvent.Completed -> notificationService.genericSuccess(
                CompactionNotifications.successText(event.payload)
            )

            is CompactionEvent.Skipped -> notificationService.genericWarning(
                CompactionNotifications.skipText(event.reason)
            )

            is CompactionEvent.ErrorOccurred -> notificationService.apiError(
                error = event.error,
                shortMessage = "Failed to compact the conversation"
            )

            // The terminal marker only closes the operation; the composer state is the progress signal.
            CompactionEvent.StreamCompleted -> Unit
        }
    }
}
