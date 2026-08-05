package eu.torvian.chatbot.app.viewmodel.chat.state

/**
 * Describes the lifecycle of the currently active assistant turn as presented by the chat controls.
 */
enum class TurnExecutionState {
    /** No turn is active and the composer can start a new message. */
    IDLE,

    /** A turn is actively generating or executing its current step. */
    RUNNING,

    /** A soft pause was requested and the current step is being allowed to finish. */
    PAUSING,

    /** A hard cancellation was requested and terminal server events are being drained. */
    STOPPING
}