package eu.torvian.chatbot.app.viewmodel

/**
 * Represents a durable navigation intent for cross-session search results.
 *
 * This intent is stored in a singleton state holder and observed by the target
 * ChatViewModel. It contains only identity/state information, not runtime objects.
 *
 * @property sessionId The session ID to navigate to.
 * @property messageId The message ID to make visible and select.
 * @property query The search query to apply in the target session.
 */
data class SearchNavigationIntent(
    val sessionId: Long,
    val messageId: Long,
    val query: String,
)
