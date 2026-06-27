package eu.torvian.chatbot.app.chat.search

import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-side state contract for in-session chat search.
 *
 * This state stays focused on the currently displayed branch and the selection state needed by the
 * top bar and chat area search UI.
 *
 * @property isSearchActive Whether the in-session search UI should currently be visible.
 * @property searchQuery Current query applied to the displayed messages.
 * @property searchResults Occurrence-level matches derived from the displayed messages and [searchQuery].
 * @property currentSearchIndex Currently selected result index, or `-1` when no match is selectable.
 */
data class ChatSearchUiState(
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<MessageSearchMatch> = emptyList(),
    val currentSearchIndex: Int = -1,
)

/**
 * Coordinates chat-screen search behavior that should not live directly in the composable.
 *
 * The coordinator owns two concerns:
 * - session-local in-session search UI state for the currently displayed branch
 * - the staged workflow that turns a cross-session search hit into the existing in-session search navigation
 *
 * Chat loading and branch switching remain outside this class and are requested through callbacks
 * when the coordinator detects that a cross-session hit is currently off-branch.
 */
class ChatSearchCoordinator {
    /** Backing state consumed by the top bar and chat area UI. */
    private val _uiState = MutableStateFlow(ChatSearchUiState())

    /** Public read-only search state for the chat screen. */
    val uiState: StateFlow<ChatSearchUiState> = _uiState.asStateFlow()

    /** Backing monotonic trigger that changes whenever a new cross-session request is staged. */
    private val _navigationRequestVersion = MutableStateFlow(0L)

    /**
     * Opaque reconciliation trigger observed by the chat screen.
     *
     * Consumers should only react to value changes. The actual numeric value has no semantic meaning
     * beyond forcing reconciliation to re-run when a fresh navigation intent is recorded.
     */
    val navigationRequestVersion: StateFlow<Long> = _navigationRequestVersion.asStateFlow()

    /** Latest displayed messages used to derive occurrence-level search matches. */
    private var displayedMessages: List<ChatMessage> = emptyList()

    /** Latest selected session identity used to reset session-local search state when context changes. */
    private var observedSelectedSessionId: Long? = null

    /** Pending cross-session navigation request, if one is currently being resolved. */
    private var pendingCrossSessionNavigation: PendingCrossSessionNavigation? = null

    /**
     * Shows the in-session search UI without changing the current query.
     */
    fun showSearch() {
        updateUiState { state ->
            state.copy(isSearchActive = true)
        }
    }

    /**
     * Closes the in-session search UI and clears the active query and selection.
     */
    fun closeSearch() {
        updateUiState {
            ChatSearchUiState()
        }
    }

    /**
     * Replaces the active query and resets the selected occurrence.
     *
     * The resulting occurrence list is re-derived from the currently displayed messages.
     *
     * @param query New query entered by the user.
     */
    fun updateSearchQuery(query: String) {
        updateUiState { state ->
            state.copy(
                searchQuery = query,
                currentSearchIndex = -1,
            )
        }
    }

    /**
     * Moves the selected occurrence forward or backward through the current result set.
     *
     * @param direction Requested navigation direction.
     */
    fun navigateSearchResult(direction: SearchDirection) {
        updateUiState { state ->
            state.copy(
                currentSearchIndex = navigateSearchIndex(
                    searchResults = state.searchResults,
                    currentIndex = state.currentSearchIndex,
                    direction = direction,
                )
            )
        }
    }

    /**
     * Selects a concrete occurrence index directly.
     *
     * @param index Zero-based result index requested by the UI.
     */
    fun jumpToSearchResult(index: Int) {
        updateUiState { state ->
            state.copy(
                currentSearchIndex = normalizeSearchIndex(state.searchResults, index)
            )
        }
    }

    /**
     * Stages navigation from a cross-session search result into the existing in-session search flow.
     *
     * The actual resolution is deferred until [onChatContextChanged] confirms that the target session
     * has been loaded and the relevant message is visible on the currently displayed branch. Each new
     * staged request also advances [navigationRequestVersion] so reconciliation can re-run even when
     * the selected or active session does not change.
     *
     * @param result Cross-session search hit selected by the user.
     * @param query Query that should be restored into the in-session search UI.
     */
    fun beginCrossSessionNavigation(result: MessageSearchResult, query: String) {
        pendingCrossSessionNavigation = PendingCrossSessionNavigation(
            sessionId = result.sessionId,
            messageId = result.messageId,
            query = query.trim(),
        )
        _navigationRequestVersion.update { version -> version + 1 }
    }

    /**
     * Reconciles the coordinator against the latest chat/session context.
     *
     * This should be called whenever authentication, selected session identity, loaded session state,
     * displayed messages, or [navigationRequestVersion] change. The coordinator will update its
     * derived occurrence list and, when a cross-session navigation is pending, request any needed
     * branch switch before selecting the target in-session result.
     *
     * @param isUserAuthenticated Whether an authenticated user is currently available.
     * @param selectedSessionId Session selected in the session list.
     * @param activeChatSessionId Session currently loaded into the active chat screen state.
     * @param isSessionLoaded Whether the active session data is fully available.
     * @param displayedMessages Messages currently rendered for the active branch.
     * @param onSwitchBranchToMessage Callback used to request a branch switch for an off-branch hit.
     */
    fun onChatContextChanged(
        isUserAuthenticated: Boolean,
        selectedSessionId: Long?,
        activeChatSessionId: Long?,
        isSessionLoaded: Boolean,
        displayedMessages: List<ChatMessage>,
        onSwitchBranchToMessage: (Long) -> Unit,
    ) {
        updateSelectedSession(selectedSessionId)
        updateDisplayedMessages(displayedMessages)

        val pendingNavigation = pendingCrossSessionNavigation ?: return
        if (!isUserAuthenticated) {
            pendingCrossSessionNavigation = null
            return
        }

        if (selectedSessionId != pendingNavigation.sessionId || activeChatSessionId != pendingNavigation.sessionId) {
            return
        }

        if (!isSessionLoaded) {
            return
        }

        if (displayedMessages.none { message -> message.id == pendingNavigation.messageId }) {
            if (!pendingNavigation.branchSwitchRequested) {
                // Search hits can land on a non-active branch, so request the switch once and wait
                // for the next context update before resolving the in-session target selection.
                onSwitchBranchToMessage(pendingNavigation.messageId)
                pendingCrossSessionNavigation = pendingNavigation.copy(branchSwitchRequested = true)
            }
            return
        }

        if (!pendingNavigation.searchActivated || !uiState.value.isSearchActive || uiState.value.searchQuery != pendingNavigation.query) {
            activateSearch(pendingNavigation.query)
            pendingCrossSessionNavigation = pendingNavigation.copy(searchActivated = true)
        }

        val targetSearchIndex = uiState.value.searchResults.indexOfFirst { match ->
            match.messageId == pendingNavigation.messageId
        }
        if (targetSearchIndex >= 0) {
            jumpToSearchResult(targetSearchIndex)
            pendingCrossSessionNavigation = null
        }
    }

    /**
     * Resets session-local search UI state when the selected session changes.
     *
     * Pending cross-session navigation is intentionally preserved so a search result click can stage
     * the navigation before the target session finishes loading.
     *
     * @param selectedSessionId Newly selected session identifier.
     */
    private fun updateSelectedSession(selectedSessionId: Long?) {
        if (observedSelectedSessionId == selectedSessionId) {
            return
        }

        observedSelectedSessionId = selectedSessionId
        displayedMessages = emptyList()
        _uiState.value = ChatSearchUiState()
    }

    /**
     * Replaces the displayed message source used for occurrence derivation.
     *
     * @param displayedMessages Messages currently visible in the chat area.
     */
    private fun updateDisplayedMessages(displayedMessages: List<ChatMessage>) {
        if (this.displayedMessages == displayedMessages) {
            return
        }

        this.displayedMessages = displayedMessages
        updateUiState { state -> state }
    }

    /**
     * Activates search mode with a specific query as part of cross-session navigation.
     *
     * @param query Query that should be restored into the in-session search controls.
     */
    private fun activateSearch(query: String) {
        updateUiState { state ->
            state.copy(
                isSearchActive = true,
                searchQuery = query,
                currentSearchIndex = -1,
            )
        }
    }

    /**
     * Applies a state transformation and re-derives occurrence state from the current messages.
     *
     * @param transform Mapping from the previous UI state to a new base state.
     */
    private fun updateUiState(transform: (ChatSearchUiState) -> ChatSearchUiState) {
        _uiState.update { previousState ->
            transform(previousState).deriveSearchState(displayedMessages)
        }
    }
}

/**
 * Tracks the staged steps required to turn a cross-session search hit into in-session navigation.
 *
 * @property sessionId Session that should be opened.
 * @property messageId Message that should become visible and selected.
 * @property query Query that should be injected into the in-session search UI.
 * @property branchSwitchRequested Whether a branch switch was already requested for this hit.
 * @property searchActivated Whether the in-session search UI was already primed with [query].
 */
private data class PendingCrossSessionNavigation(
    val sessionId: Long,
    val messageId: Long,
    val query: String,
    val branchSwitchRequested: Boolean = false,
    val searchActivated: Boolean = false,
)

/**
 * Recomputes occurrence data for the current query and clamps the active selection accordingly.
 *
 * @receiver Base UI state before derived occurrence fields are refreshed.
 * @param displayedMessages Messages currently rendered in the chat area.
 * @return A state instance with up-to-date occurrence data.
 */
private fun ChatSearchUiState.deriveSearchState(displayedMessages: List<ChatMessage>): ChatSearchUiState {
    val searchResults = findSearchMatches(displayedMessages, searchQuery)
    val normalizedSearchIndex = normalizeSearchIndex(searchResults, currentSearchIndex)
    return copy(
        searchResults = searchResults,
        currentSearchIndex = normalizedSearchIndex,
    )
}
