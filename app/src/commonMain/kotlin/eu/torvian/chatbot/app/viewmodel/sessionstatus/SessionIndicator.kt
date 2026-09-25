package eu.torvian.chatbot.app.viewmodel.sessionstatus

/**
 * The single indicator kind rendered on a chat session row, ordered by display priority.
 *
 * At most one indicator is shown per row: [REQUESTING_INPUT] outranks [BUSY], which outranks both
 * completion states.
 */
enum class SessionIndicator {
    /** The session's agent is blocked waiting for a decision from the user. */
    REQUESTING_INPUT,

    /** A turn (LLM generation plus the whole tool-call loop) is running in the session. */
    BUSY,

    /** The session's last turn just finished successfully; the flag is transient. */
    COMPLETED_SUCCESS,

    /** The session's last turn just finished with a failure; the flag is transient. */
    COMPLETED_FAILURE
}