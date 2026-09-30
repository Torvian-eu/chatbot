package eu.torvian.chatbot.app.viewmodel.sessionstatus

import eu.torvian.chatbot.app.domain.TurnOutcome
import eu.torvian.chatbot.app.domain.events.TurnLifecycleTrigger
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.SessionSelectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * In-memory [SessionTurnStatusRegistry] backed by a single state map.
 *
 * It observes [eu.torvian.chatbot.app.viewmodel.SessionSelectionController] so that selecting a session clears its completion flag
 * regardless of where the selection came from. Entries whose status returns to all-default are
 * dropped so the map stays bounded by recently active turns.
 *
 * The same lifecycle signals feed the out-of-app turn alerts, because every turn reports here: a
 * finished turn publishes its raw outcome before the indicator suppression is applied, and a
 * transition into the awaiting state publishes one trigger.
 *
 * @property sessionSelectionController Shared selection state that drives clear-on-select.
 * @property eventBus Bus the out-of-app alert triggers are published on.
 * @param scope Coroutine scope hosting the selection observer; injectable for deterministic tests.
 */
class InMemorySessionTurnStatusRegistry(
    private val sessionSelectionController: SessionSelectionController,
    private val eventBus: EventBus,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : SessionTurnStatusRegistry {

    private companion object {
        val logger = kmpLogger<InMemorySessionTurnStatusRegistry>()
    }

    private val _statuses = MutableStateFlow<Map<Long, SessionTurnStatus>>(emptyMap())

    override val statuses: StateFlow<Map<Long, SessionTurnStatus>> = _statuses.asStateFlow()

    init {
        scope.launch {
            sessionSelectionController.selectedSessionId.collect { selectedSessionId ->
                if (selectedSessionId != null) {
                    clearCompletion(selectedSessionId)
                }
            }
        }
    }

    override fun onTurnStarted(sessionId: Long) {
        _statuses.update { current ->
            // A new turn replaces any leftover completion flag, so an interrupted turn cannot leave
            // the previous turn's outcome visible on the row.
            val started = (current[sessionId] ?: SessionTurnStatus()).copy(
                isTurnActive = true,
                lastOutcome = null
            )
            current.putOrDrop(sessionId, started)
        }
    }

    override fun onTurnAwaitingInput(sessionId: Long, awaiting: Boolean) {
        // Only entering the awaiting state alerts: a turn deferring several tool calls at once
        // reports `true` once per call, and each of those must not raise its own alert.
        val wasAwaiting = _statuses.value[sessionId]?.isAwaitingInput == true
        _statuses.update { current ->
            val updated = (current[sessionId] ?: SessionTurnStatus()).copy(isAwaitingInput = awaiting)
            current.putOrDrop(sessionId, updated)
        }
        if (awaiting && !wasAwaiting) {
            publishTurnAlert(TurnLifecycleTrigger.AwaitingApproval(sessionId))
        }
    }

    override fun onTurnFinished(sessionId: Long, outcome: TurnOutcome?) {
        _statuses.update { current ->
            val finished = (current[sessionId] ?: SessionTurnStatus()).copy(
                isTurnActive = false,
                isAwaitingInput = false,
                // Suppressing the flag for the selected session prevents a badge from flashing on
                // the row the user is watching right now.
                lastOutcome = outcome?.takeIf { sessionId != sessionSelectionController.selectedSessionId.value }
            )
            current.putOrDrop(sessionId, finished)
        }
        // The alert carries the unmodified outcome: it must fire while the user is looking at
        // another application with this session selected, which is exactly when the badge is hidden.
        if (outcome != null) {
            publishTurnAlert(TurnLifecycleTrigger.TurnCompleted(sessionId, outcome))
        }
    }

    override fun clearCompletion(sessionId: Long) {
        _statuses.update { current ->
            val status = current[sessionId] ?: return@update current
            current.putOrDrop(sessionId, status.copy(lastOutcome = null))
        }
    }

    /**
     * Publishes one out-of-app alert trigger without suspending.
     *
     * Called on the turn's own path, so a dropped trigger may only cost an alert and must never
     * affect the turn or the indicator state.
     *
     * @param trigger Turn event to publish.
     */
    private fun publishTurnAlert(trigger: TurnLifecycleTrigger) {
        if (!eventBus.tryEmitEvent(trigger)) {
            // Warned because a full buffer means a lost alert the user was supposed to receive;
            // this is the only trace of it.
            logger.warn("Turn alert event dropped: the event bus buffer is full")
        }
    }

    /**
     * Stores [status] for [sessionId], or removes the entry when every field is back to default.
     *
     * @receiver The status map being updated.
     * @param sessionId Session the status belongs to.
     * @param status New status of the session.
     * @return The updated map with the entry set or dropped.
     */
    private fun Map<Long, SessionTurnStatus>.putOrDrop(
        sessionId: Long,
        status: SessionTurnStatus
    ): Map<Long, SessionTurnStatus> =
        if (status == SessionTurnStatus()) this - sessionId else this + (sessionId to status)
}
