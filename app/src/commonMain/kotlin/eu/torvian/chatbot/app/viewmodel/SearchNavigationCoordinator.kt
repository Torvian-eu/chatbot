package eu.torvian.chatbot.app.viewmodel

import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel

/**
 * Coordinator for cross-session search navigation workflow.
 *
 * This coordinator provides a simple method-based API for initiating navigation
 * to a specific message from cross-session search results. It sets a durable intent
 * and requests session selection, allowing the target ChatViewModel to observe and
 * react to the navigation request.
 *
 * @property sessionSelectionController Controller for session selection.
 * @property navigationState State holder for navigation intent.
 */
class SearchNavigationCoordinator(
    private val sessionSelectionController: SessionSelectionController,
    private val navigationState: SearchNavigationState,
) {

    companion object {
        private val logger = kmpLogger<SearchNavigationCoordinator>()
    }

    /**
     * Initiates navigation to a specific message from cross-session search results.
     *
     * This method sets a durable [SearchNavigationIntent] and requests session selection.
     * The target [ChatViewModel] will observe the intent and perform branch switching
     * and search activation when the session is loaded and active.
     *
     * @param sessionId The session ID to navigate to.
     * @param messageId The message ID to make visible and select.
     * @param query The search query to apply in the target session.
     */
    fun startNavigation(sessionId: Long, messageId: Long, query: String) {
        val intent = SearchNavigationIntent(
            sessionId = sessionId,
            messageId = messageId,
            query = query,
        )

        navigationState.setIntent(intent)
        sessionSelectionController.selectSession(sessionId)

        logger.debug("Set navigation intent for session $sessionId, message $messageId")
    }
}
