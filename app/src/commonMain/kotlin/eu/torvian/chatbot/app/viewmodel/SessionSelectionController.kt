package eu.torvian.chatbot.app.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton shared selection contract for session selection.
 *
 * This provides a single source of truth for the currently selected session ID,
 * used by both the session list UI and cross-session navigation coordination.
 */
interface SessionSelectionController {
    /**
     * The currently selected session ID.
     */
    val selectedSessionId: StateFlow<Long?>

    /**
     * Selects a session by ID.
     *
     * @param sessionId The ID of the session to select, or null to clear selection.
     */
    fun selectSession(sessionId: Long?)
}

/**
 * Default implementation of [SessionSelectionController] using MutableStateFlow.
 */
class DefaultSessionSelectionController : SessionSelectionController {
    private val _selectedSessionId = MutableStateFlow<Long?>(null)

    override val selectedSessionId: StateFlow<Long?> = _selectedSessionId.asStateFlow()

    override fun selectSession(sessionId: Long?) {
        _selectedSessionId.value = sessionId
    }
}
