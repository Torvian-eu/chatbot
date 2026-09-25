package eu.torvian.chatbot.app.viewmodel.sessionstatus

/**
 * Terminal outcome of a finished turn that earns a completion badge.
 *
 * Interrupted turns deliberately have no outcome at all: the user caused the ending, so neither a
 * success nor a failure badge is shown.
 */
enum class TurnOutcome {
    /** The turn ended with a completed final assistant message. */
    SUCCESS,

    /** The turn ended with a failed final assistant message or a turn-ending error. */
    FAILURE
}