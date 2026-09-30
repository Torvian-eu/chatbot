package eu.torvian.chatbot.app.domain.events

import eu.torvian.chatbot.app.domain.TurnOutcome

/**
 * A single turn event worth alerting about outside the app.
 *
 * Triggers are published on the shared application event bus by the turn-status registry, which is
 * the one place every turn reports its lifecycle to. They are internal events: nothing renders them
 * in-app, and only the out-of-app alert dispatcher consumes them.
 */
sealed class TurnLifecycleTrigger : InternalEvent() {
    /** Session the triggering turn belongs to. */
    abstract val sessionId: Long

    /**
     * A turn reached a terminal state the user should know about.
     *
     * @property sessionId Session the turn belongs to.
     * @property outcome Terminal classification of the turn; interruption and other inconclusive
     *           endings produce no trigger at all.
     */
    data class TurnCompleted(
        override val sessionId: Long,
        val outcome: TurnOutcome
    ) : TurnLifecycleTrigger()

    /**
     * A turn stopped and is waiting for the user to decide on a tool call.
     *
     * Raised once per transition into the awaiting state: a turn that defers several tool calls at
     * once produces one trigger, and a later deferral after a decision produces another.
     *
     * @property sessionId Session the turn belongs to.
     */
    data class AwaitingApproval(
        override val sessionId: Long
    ) : TurnLifecycleTrigger()
}
