package eu.torvian.chatbot.app.service.agent

import eu.torvian.chatbot.app.utils.misc.KmpLogger
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel
import eu.torvian.chatbot.common.models.agent.OperatorToolMode
import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Logger used by the shared operator-tool turn driver. Tagged independently of any executor so the
 * same driver serves `spawn_agent` and `send_message` diagnostics without rebinding per class.
 */
private val TURN_DRIVER_LOGGER: KmpLogger = createKmpLogger("OperatorToolTurnDriver")

/**
 * Maximum time to wait for the target session's role/model/settings to become resolvable after
 * loading. The load use case completes before the ChatState derivations propagate, so the turn
 * driver waits up to this long before refusing the send.
 */
private val ROLE_RESOLUTION_TIMEOUT: Duration = 20.seconds

/**
 * Outcome of driving a chat turn through the target session's [ChatViewModel], shared by the
 * `spawn_agent` and `send_message` paths so each tool executor can format its exact result strings.
 */
internal sealed interface TurnOutcome {

    /**
     * The driven turn reached a terminal state.
     *
     * @property content The last assistant message text in wait mode; `null` in fire-and-forget
     *            mode, where the turn is still running in the background.
     */
    data class Succeeded(val content: String?) : TurnOutcome

    /**
     * The target VM refused to start the send ([ChatViewModel.sendMessage] returned `null`).
     */
    data object Refused : TurnOutcome

    /**
     * Wait mode: the injected turn produced no new, non-blank assistant message.
     */
    data object NoAssistantMessage : TurnOutcome

    /**
     * The session's role/model/settings could not be resolved within the bounded wait.
     */
    data object UnresolvedRole : TurnOutcome
}

/**
 * Drives a chat turn through the target session's [ChatViewModel] and reports the outcome.
 *
 * Keeps the target ChatState flows subscribed for the whole turn: its derived flows
 * (`sessionDataState`, `currentAgentRole`, `currentModel`, `currentSettings`) use
 * `SharingStarted.WhileSubscribed`, so a headless VM with no UI subscriber never computes them
 * and [ChatViewModel.sendMessage]'s role guard would refuse to run. After the load job completes
 * the driver waits (bounded) until the role's model/settings resolve, then sets the input and
 * sends.
 *
 * In [OperatorToolMode.FIRE_AND_FORGET] mode the driver returns [TurnOutcome.Succeeded] right after
 * the send starts — it never joins the send job, so the turn continues in the target VM's own scope.
 * In [OperatorToolMode.WAIT_FOR_RESPONSE] mode it joins the send job and then applies the newness
 * guard: the call requires the branch's last assistant message to be a **new** message (id not
 * present before the send), so a history-bearing target whose injected turn produces no response
 * never returns a stale previous message; blank content is likewise rejected as
 * [TurnOutcome.NoAssistantMessage].
 *
 * @param viewModel The target session's ChatViewModel (same instance the UI uses).
 * @param sessionId The target session's identifier.
 * @param userId The authenticated user's identifier.
 * @param message The user message to inject into the target session.
 * @param mode The shared operator-tool execution mode.
 * @return The [TurnOutcome] the calling tool executor formats into its exact result strings.
 */
internal suspend fun runTurnThroughViewModel(
    viewModel: ChatViewModel,
    sessionId: Long,
    userId: Long,
    message: String,
    mode: OperatorToolMode
): TurnOutcome = coroutineScope {
    // Keep the derived ChatState flows subscribed while the turn runs: they use
    // `SharingStarted.WhileSubscribed`, so a headless VM with no UI subscriber never computes
    // them and sendMessage's role guard would refuse to run. The collectors live in a private
    // warm-up scope that is cancelled on every exit path (normal, early-return, and cancellation),
    // so they are neither awaited by this scope nor leaked past the turn.
    val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        warmUpScope.launch { viewModel.sessionDataState.collect {} }
        warmUpScope.launch { viewModel.currentAgentRole.collect {} }
        warmUpScope.launch { viewModel.currentModel.collect {} }
        warmUpScope.launch { viewModel.currentSettings.collect {} }
        warmUpScope.launch { viewModel.displayedMessages.collect {} }

        val loadJob = viewModel.loadSession(sessionId, userId)
        loadJob.join()

        // The load use case completes before the derived ChatState flows propagate; wait (bounded)
        // until the role's model/settings are resolvable so sendMessage's guard does not refuse.
        val roleResolved = withTimeoutOrNull(ROLE_RESOLUTION_TIMEOUT) {
            viewModel.currentAgentRole.first { it != null }
            viewModel.currentModel.first { it != null }
            viewModel.currentSettings.first { it != null }
            true
        } ?: false
        if (!roleResolved) {
            TURN_DRIVER_LOGGER.error("Session $sessionId could not resolve role/model/settings; refusing to send")
            return@coroutineScope TurnOutcome.UnresolvedRole
        }

        // Snapshot the assistant messages visible before the injected turn so wait mode can
        // require the returned message to be NEW: a history-bearing target whose turn produces
        // no response must not report its previous assistant message as the tool result.
        val preSendAssistantMessageIds = viewModel.displayedMessages.value
            .filterIsInstance<ChatMessage.AssistantMessage>()
            .map { it.id }
            .toSet()

        viewModel.updateInput(message)
        val sendJob = viewModel.sendMessage()
            ?: return@coroutineScope TurnOutcome.Refused

        if (mode == OperatorToolMode.FIRE_AND_FORGET) {
            // Immediate mode: the send has started in the target VM's own scope; report success
            // without joining — the background turn terminates via its own session lifecycle.
            return@coroutineScope TurnOutcome.Succeeded(content = null)
        }

        sendJob.join()

        // Newness guard (wait mode only): the last assistant message must be new and non-blank.
        val lastAssistantMessage = viewModel.displayedMessages.value
            .filterIsInstance<ChatMessage.AssistantMessage>()
            .lastOrNull()
        if (lastAssistantMessage == null ||
            lastAssistantMessage.id in preSendAssistantMessageIds ||
            lastAssistantMessage.content.isBlank()
        ) {
            return@coroutineScope TurnOutcome.NoAssistantMessage
        }
        TurnOutcome.Succeeded(content = lastAssistantMessage.content)
    } finally {
        warmUpScope.cancel()
    }
}