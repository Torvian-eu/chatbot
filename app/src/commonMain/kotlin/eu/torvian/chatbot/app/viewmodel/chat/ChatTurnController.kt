package eu.torvian.chatbot.app.viewmodel.chat

import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import eu.torvian.chatbot.app.viewmodel.chat.usecase.CompactConversationUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SendMessageUseCase
import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns the composer's turn and compaction state machine for [ChatState].
 *
 * Starts sends, routes the composer action to a soft pause or hard stop, and hard-cancels an
 * in-flight send. All work runs on the supplied [scope], so cancelling that scope cancels the
 * controller's jobs.
 *
 * @param state The shared chat state consulted for the turn-execution state.
 * @param sendMessageUC Use case that performs a send and exposes pause/cancel requests.
 * @param compactConversationUC Use case cancelled when the composer action arrives mid-compaction.
 * @param scope Scope that owns the send and cancellation-drain jobs.
 */
class ChatTurnController(
    private val state: ChatState,
    private val sendMessageUC: SendMessageUseCase,
    private val compactConversationUC: CompactConversationUseCase,
    private val scope: CoroutineScope
) {

    companion object {
        /** Maximum time allowed for the server to publish cancellation events before a hard cancel. */
        private const val CANCELLATION_DRAIN_TIMEOUT_MILLIS = 3_000L
    }

    /**
     * Whether an assistant turn is currently in progress (RUNNING, PAUSING, or STOPPING).
     *
     * Conversation-mutating and turn-starting actions must not run while a turn is active.
     * This is a defense-in-depth guard behind the disabled UI controls: even if a caller
     * bypasses the UI, no conflicting generation or destructive edit can start mid-turn.
     */
    val isTurnActive: Boolean
        get() = state.turnExecutionState.value != TurnExecutionState.IDLE

    /**
     * Job tracking the currently active message sending operation.
     * Null when no message is being sent.
     */
    private var sendMessageJob: Job? = null

    /** Job that bounds the time spent waiting for the server to acknowledge cancellation. */
    private var cancellationDrainJob: Job? = null

    /**
     * Sends the current message content to the active session, or continues from a specific message.
     *
     * @param continueFromMessage When provided, uses Branch & Continue mode: sends null content
     *            with this message's ID as parentMessageId to continue the conversation
     *            from that point. When null, sends the current input content normally.
     * @return The [Job] that performs the send and completes when the turn ends, or `null` when the
     *         send is refused: a turn is already active, the input is blank in normal mode, or the
     *         session's role/model/settings cannot be resolved. The last two mirror the guards inside
     *         [SendMessageUseCase.execute] so the spawned-turn coordinator can observe a refusal
     *         deterministically instead of awaiting a silently-completed no-op turn.
     */
    fun sendMessage(continueFromMessage: ChatMessage? = null): Job? {
        // Refuse to start a new turn while one is already active. This covers regular sends,
        // Branch & Continue, and any caller that bypasses the disabled UI controls.
        if (isTurnActive) return null
        // Normal-mode sends need non-blank input; the spawned-turn path always sets the input first.
        if (continueFromMessage == null && state.inputContent.value.isBlank()) return null
        // A session role with a resolvable model/settings profile is required for a turn to run.
        // Returning null here lets the spawned-turn executor report a deterministic tool error.
        if (state.currentAgentRole.value == null || state.currentModel.value == null || state.currentSettings.value == null) {
            return null
        }
        val job = scope.launch {
            sendMessageUC.execute(continueFromMessage = continueFromMessage)
        }
        sendMessageJob = job
        state.setTurnExecutionState(TurnExecutionState.RUNNING)
        job.invokeOnCompletion {
            sendMessageJob = null
            // STOPPING owns the final transition until the cancellation drain completes.
            if (state.turnExecutionState.value != TurnExecutionState.STOPPING) {
                state.setTurnExecutionState(TurnExecutionState.IDLE)
            }
        }
        return job
    }

    /**
     * Routes the composer action to a soft pause or hard stop according to the active turn state.
     *
     * RUNNING deliberately sends only Pause, while PAUSING sends Cancel and starts the bounded
     * drain. STOPPING is intentionally inert because another click cannot improve cancellation.
     * COMPACTING has nothing to drain: the compaction socket is cancelled directly.
     */
    fun handlePauseOrStop() {
        when (state.turnExecutionState.value) {
            TurnExecutionState.RUNNING -> {
                state.setTurnExecutionState(TurnExecutionState.PAUSING)
                scope.launch {
                    sendMessageUC.requestPause()
                }
            }

            TurnExecutionState.PAUSING -> {
                state.setTurnExecutionState(TurnExecutionState.STOPPING)
                val activeSendJob = sendMessageJob ?: run {
                    state.setTurnExecutionState(TurnExecutionState.IDLE)
                    return
                }
                if (cancellationDrainJob?.isActive == true) return

                // Keep collecting the socket so terminal CANCELLED events reach the UI before closure.
                cancellationDrainJob = scope.launch {
                    sendMessageUC.requestCancellation()
                    withTimeoutOrNull(CANCELLATION_DRAIN_TIMEOUT_MILLIS.milliseconds) {
                        activeSendJob.join()
                    }
                    if (activeSendJob.isActive) {
                        // A broken or stuck peer must not leave the send state active forever.
                        activeSendJob.cancel()
                    }
                }.also { drainJob ->
                    drainJob.invokeOnCompletion {
                        cancellationDrainJob = null
                        state.setTurnExecutionState(TurnExecutionState.IDLE)
                    }
                }
            }

            TurnExecutionState.STOPPING,
            TurnExecutionState.IDLE -> Unit

            TurnExecutionState.COMPACTING -> compactConversationUC.cancel()
        }
    }

    /**
     * Hard-cancels the in-flight send without the pause/stop state machine.
     *
     * This is the cancellation hook for the spawned-turn coordinator: a spawned send runs on the
     * shared `scope`, so it survives the primary socket closing on its own. When the
     * primary turn ends mid-spawn, the coordinator calls this to stop the spawned turn: it emits a
     * [eu.torvian.chatbot.common.models.api.core.ChatClientEvent.Cancel] so the server cancels the
     * turn on the spawned socket, then cancels the send job so the socket is closed and the turn
     * state falls back to IDLE through the job's completion handler. Deliberately not routed through
     * [handlePauseOrStop], whose RUNNING branch only sends a soft Pause.
     */
    fun forceCancelSend() {
        val activeSendJob = sendMessageJob
        if (activeSendJob == null || !activeSendJob.isActive) return
        scope.launch {
            sendMessageUC.requestCancellation()
        }
        activeSendJob.cancel()
    }
}
