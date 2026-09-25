package eu.torvian.chatbot.app.viewmodel.sessionstatus

/**
 * In-memory status of one chat session with a turn owned by this app instance.
 *
 * @property isTurnActive Whether a turn started by this app instance is currently running.
 * @property isAwaitingInput Whether the session's agent is blocked on a user decision; modeled
 *           generically so any user-input request (e.g. a future user-dialog tool) can raise it.
 * @property lastOutcome Transient outcome of the last finished turn, cleared when the session is
 *           selected; `null` for interrupted or inconclusive endings.
 */
data class SessionTurnStatus(
    val isTurnActive: Boolean = false,
    val isAwaitingInput: Boolean = false,
    val lastOutcome: TurnOutcome? = null
) {
    /**
     * Highest-priority indicator for the row, or `null` when the row shows nothing.
     */
    val indicator: SessionIndicator?
        get() = when {
            isAwaitingInput -> SessionIndicator.REQUESTING_INPUT
            isTurnActive -> SessionIndicator.BUSY
            lastOutcome == TurnOutcome.SUCCESS -> SessionIndicator.COMPLETED_SUCCESS
            lastOutcome == TurnOutcome.FAILURE -> SessionIndicator.COMPLETED_FAILURE
            else -> null
        }
}
