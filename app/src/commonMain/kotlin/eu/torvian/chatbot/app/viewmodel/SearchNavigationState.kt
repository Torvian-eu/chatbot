package eu.torvian.chatbot.app.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton state holder for cross-session search navigation intent.
 *
 * This provides durable state that survives across configuration changes and
 * allows the target ChatViewModel to observe and react to navigation requests
 * even if the session loads after the intent is set.
 */
interface SearchNavigationState {
    /**
     * The current navigation intent, or null if no navigation is pending.
     */
    val intent: StateFlow<SearchNavigationIntent?>

    /**
     * Sets the navigation intent.
     *
     * @param intent The intent to set, or null to clear.
     */
    fun setIntent(intent: SearchNavigationIntent?)

    /**
     * Clears the current navigation intent.
     */
    fun clearIntent()
}

/**
 * Default implementation of [SearchNavigationState] using MutableStateFlow.
 */
class DefaultSearchNavigationState : SearchNavigationState {
    private val _intent = MutableStateFlow<SearchNavigationIntent?>(null)

    override val intent: StateFlow<SearchNavigationIntent?> = _intent.asStateFlow()

    override fun setIntent(intent: SearchNavigationIntent?) {
        _intent.value = intent
    }

    override fun clearIntent() {
        _intent.value = null
    }
}
