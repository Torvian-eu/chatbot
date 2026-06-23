package eu.torvian.chatbot.app.compose.chatarea

/**
 * Direction used when cycling through in-session search results.
 */
enum class SearchDirection {
    /** Selects the previous matching message, wrapping to the end when needed. */
    BACKWARD,

    /** Selects the next matching message, wrapping to the start when needed. */
    FORWARD,
}
