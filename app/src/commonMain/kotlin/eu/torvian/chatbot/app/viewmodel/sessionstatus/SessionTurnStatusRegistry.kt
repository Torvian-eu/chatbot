package eu.torvian.chatbot.app.viewmodel.sessionstatus

import eu.torvian.chatbot.app.domain.TurnOutcome
import eu.torvian.chatbot.app.domain.events.TurnLifecycleTrigger
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the per-session turn status indicators shown in the session list.
 *
 * Only turns owned by this app instance are tracked; the state is in-memory and dies with the app.
 *
 * Implementations also publish the out-of-app alert triggers ([TurnLifecycleTrigger]) derived from
 * the same signals, because this is the one place every turn reports its lifecycle to. Those triggers
 * are deliberately unaffected by the indicator suppression described on [onTurnFinished].
 */
interface SessionTurnStatusRegistry {
    /**
     * Current status per session ID. Sessions without interesting state are absent.
     */
    val statuses: StateFlow<Map<Long, SessionTurnStatus>>

    /**
     * Marks a turn as running in [sessionId], replacing any leftover completion flag.
     *
     * @param sessionId Session whose turn just started.
     */
    fun onTurnStarted(sessionId: Long)

    /**
     * Sets whether the session's agent is currently blocked on a user decision.
     *
     * Entering that state raises one out-of-app alert for the session; reporting it again while it
     * stays pending raises none.
     *
     * @param sessionId Session whose turn changed its awaiting-input state.
     * @param awaiting Whether a user decision is required to continue the turn.
     */
    fun onTurnAwaitingInput(sessionId: Long, awaiting: Boolean)

    /**
     * Settles the turn in [sessionId] with its terminal [outcome].
     *
     * The completion flag is only recorded when the session is not selected at this moment: a
     * selected session is watched live, so its rows never carry a completion badge.
     *
     * A non-null [outcome] also raises the out-of-app turn alert for the session, independently of
     * that suppression: the user may have switched to another application while watching this very
     * session, which is exactly the case the alert exists for.
     *
     * @param sessionId Session whose turn just ended.
     * @param outcome Completion badge the turn earns, or `null` for interrupted/inconclusive endings.
     */
    fun onTurnFinished(sessionId: Long, outcome: TurnOutcome?)

    /**
     * Drops the transient completion flag of [sessionId] without touching live turn state.
     *
     * @param sessionId Session whose completion flag should be cleared.
     */
    fun clearCompletion(sessionId: Long)
}

