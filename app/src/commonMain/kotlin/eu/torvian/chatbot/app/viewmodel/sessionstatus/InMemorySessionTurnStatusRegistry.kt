package eu.torvian.chatbot.app.viewmodel.sessionstatus

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
 * @property sessionSelectionController Shared selection state that drives clear-on-select.
 * @param scope Coroutine scope hosting the selection observer; injectable for deterministic tests.
 */
class InMemorySessionTurnStatusRegistry(
    private val sessionSelectionController: SessionSelectionController,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : SessionTurnStatusRegistry {

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
        _statuses.update { current ->
            val updated = (current[sessionId] ?: SessionTurnStatus()).copy(isAwaitingInput = awaiting)
            current.putOrDrop(sessionId, updated)
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
    }

    override fun clearCompletion(sessionId: Long) {
        _statuses.update { current ->
            val status = current[sessionId] ?: return@update current
            current.putOrDrop(sessionId, status.copy(lastOutcome = null))
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