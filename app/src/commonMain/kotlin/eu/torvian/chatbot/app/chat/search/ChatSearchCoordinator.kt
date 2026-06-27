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
 * @property rollbackTarget Previously displayed thread that can be restored after search-driven branch switching.
 * @property rollbackContextSessionId Session in which [rollbackTarget] is currently meaningful to offer.
 * @property currentSessionId Session that currently owns this session-local search UI state.
 */
data class ChatSearchUiState(
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<MessageSearchMatch> = emptyList(),
    val currentSearchIndex: Int = -1,
    val rollbackTarget: PreviousThreadRollbackTarget? = null,
    val rollbackContextSessionId: Long? = null,
    val currentSessionId: Long? = null,
) {
    /**
     * Whether the UI should currently offer the lightweight rollback action.
     */
    val canReturnToPreviousThread: Boolean
        get() =
            isSearchActive &&
                rollbackTarget != null &&
                rollbackContextSessionId != null &&
                rollbackContextSessionId == currentSessionId
}

/**
 * Identifies a previously displayed thread that can be restored after search-driven navigation changed branches.
 *
 * @property sessionId Session that originally contained the displayed thread.
 * @property leafMessageId Previously displayed branch leaf that anchors the exact thread to restore.
 */
data class PreviousThreadRollbackTarget(
    val sessionId: Long,
    val leafMessageId: Long,
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

    /** Backing monotonic trigger that changes whenever a staged coordinator action needs reconciliation. */
    private val _reconciliationVersion = MutableStateFlow(0L)

    /**
     * Opaque reconciliation trigger observed by the chat screen.
     *
     * Consumers should only react to value changes. The actual numeric value has no semantic meaning
     * beyond forcing reconciliation to re-run when a fresh staged action is recorded.
     */
    val reconciliationVersion: StateFlow<Long> = _reconciliationVersion.asStateFlow()

    /** Latest displayed messages used to derive occurrence-level search matches. */
    private var displayedMessages: List<ChatMessage> = emptyList()

    /** Latest selected session identity used to reset session-local search state when context changes. */
    private var observedSelectedSessionId: Long? = null

    /** Latest displayed branch leaf observed for the currently loaded session. */
    private var observedCurrentLeafMessageId: Long? = null

    /** Pending cross-session navigation request, if one is currently being resolved. */
    private var pendingCrossSessionNavigation: PendingCrossSessionNavigation? = null

    /** Pending rollback request that is waiting for the previous session/branch to become active again. */
    private var pendingPreviousThreadReturn: PendingPreviousThreadReturn? = null

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
     *
     * The rollback state is intentionally preserved so users can still return after dismissing search
     * and reopening it in the same session context.
     */
    fun closeSearch() {
        updateUiState { state ->
            ChatSearchUiState(
                rollbackTarget = state.rollbackTarget,
                rollbackContextSessionId = state.rollbackContextSessionId,
                currentSessionId = state.currentSessionId,
            )
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
     * staged request also advances [reconciliationVersion] so reconciliation can re-run even when the
     * selected or active session does not change.
     *
     * Any previous rollback target is cleared up front so the affordance only reappears when this new
     * search-driven navigation actually forces a branch switch.
     *
     * @param result Cross-session search hit selected by the user.
     * @param query Query that should be restored into the in-session search UI.
     */
    fun beginCrossSessionNavigation(result: MessageSearchResult, query: String) {
        pendingPreviousThreadReturn = null
        clearRollbackTarget()
        pendingCrossSessionNavigation = PendingCrossSessionNavigation(
            sessionId = result.sessionId,
            messageId = result.messageId,
            query = query.trim(),
            previousSessionId = observedSelectedSessionId,
            previousLeafMessageId = observedCurrentLeafMessageId,
        )
        advanceReconciliationVersion()
    }

    /**
     * Starts restoration of the previously displayed thread when a rollback target is available.
     *
     * Session selection still lives outside the coordinator, so the caller provides the callback used
     * to request reopening the original session before the leaf-based branch restoration completes.
     *
     * @param onSelectSession Callback used to activate the session identified by the rollback target.
     */
    fun returnToPreviousThread(onSelectSession: (Long) -> Unit) {
        val rollbackTarget = uiState.value.rollbackTarget ?: return
        pendingCrossSessionNavigation = null
        pendingPreviousThreadReturn = PendingPreviousThreadReturn(
            sessionId = rollbackTarget.sessionId,
            leafMessageId = rollbackTarget.leafMessageId,
        )
        // Rollback can target the session that is already selected, so advance the reconciliation
        // trigger before deciding whether an external session change will occur.
        advanceReconciliationVersion()

        if (observedSelectedSessionId != rollbackTarget.sessionId) {
            onSelectSession(rollbackTarget.sessionId)
        }
    }

    /**
     * Reconciles the coordinator against the latest chat/session context.
     *
     * This should be called whenever authentication, selected session identity, loaded session state,
     * displayed messages, or [reconciliationVersion] change. The coordinator will update its derived
     * occurrence list and, when a cross-session navigation or rollback is pending, request any needed
     * branch switch before completing the staged action.
     *
     * @param isUserAuthenticated Whether an authenticated user is currently available.
     * @param selectedSessionId Session selected in the session list.
     * @param activeChatSessionId Session currently loaded into the active chat screen state.
     * @param isSessionLoaded Whether the active session data is fully available.
     * @param currentLeafMessageId Current leaf of the branch presently displayed for the active session.
     * @param displayedMessages Messages currently rendered for the active branch.
     * @param onSwitchBranchToMessage Callback used to request a branch switch for an off-branch hit.
     */
    fun onChatContextChanged(
        isUserAuthenticated: Boolean,
        selectedSessionId: Long?,
        activeChatSessionId: Long?,
        isSessionLoaded: Boolean,
        currentLeafMessageId: Long?,
        displayedMessages: List<ChatMessage>,
        onSwitchBranchToMessage: (Long) -> Unit,
    ) {
        updateSelectedSession(selectedSessionId)
        updateDisplayedMessages(displayedMessages)
        updateObservedCurrentLeaf(
            selectedSessionId = selectedSessionId,
            activeChatSessionId = activeChatSessionId,
            isSessionLoaded = isSessionLoaded,
            currentLeafMessageId = currentLeafMessageId,
        )

        if (!isUserAuthenticated) {
            pendingCrossSessionNavigation = null
            pendingPreviousThreadReturn = null
            observedCurrentLeafMessageId = null
            return
        }

        val pendingThreadReturn = pendingPreviousThreadReturn
        if (pendingThreadReturn != null) {
            if (selectedSessionId != pendingThreadReturn.sessionId || activeChatSessionId != pendingThreadReturn.sessionId) {
                return
            }

            if (!isSessionLoaded) {
                return
            }

            if (displayedMessages.none { message -> message.id == pendingThreadReturn.leafMessageId }) {
                if (!pendingThreadReturn.branchSwitchRequested) {
                    // The previous session may reopen on a different active branch, so restore the
                    // exact prior thread by switching with the remembered leaf anchor once.
                    onSwitchBranchToMessage(pendingThreadReturn.leafMessageId)
                    pendingPreviousThreadReturn = pendingThreadReturn.copy(branchSwitchRequested = true)
                }
                return
            }

            pendingPreviousThreadReturn = null
            clearRollbackTarget()
            return
        }

        val pendingNavigation = pendingCrossSessionNavigation ?: return
        if (selectedSessionId != pendingNavigation.sessionId || activeChatSessionId != pendingNavigation.sessionId) {
            return
        }

        if (!isSessionLoaded) {
            return
        }

        if (displayedMessages.none { message -> message.id == pendingNavigation.messageId }) {
            if (!pendingNavigation.branchSwitchRequested) {
                captureRollbackTarget(
                    previousSessionId = pendingNavigation.previousSessionId,
                    previousLeafMessageId = pendingNavigation.previousLeafMessageId,
                    rollbackContextSessionId = pendingNavigation.sessionId,
                )
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
     * Pending cross-session navigation and rollback state are intentionally preserved so staged flows
     * can survive the asynchronous session switch that follows a search result click.
     *
     * @param selectedSessionId Newly selected session identifier.
     */
    private fun updateSelectedSession(selectedSessionId: Long?) {
        if (observedSelectedSessionId == selectedSessionId) {
            return
        }

        observedSelectedSessionId = selectedSessionId
        if (selectedSessionId == null) {
            observedCurrentLeafMessageId = null
        }
        displayedMessages = emptyList()
        val currentState = _uiState.value
        _uiState.value = ChatSearchUiState(
            rollbackTarget = currentState.rollbackTarget,
            rollbackContextSessionId = currentState.rollbackContextSessionId,
            currentSessionId = selectedSessionId,
        )
    }

    /**
     * Records the latest displayed leaf for the active session so branch rollback can reuse it later.
     *
     * The coordinator intentionally ignores transient states where the selected session and loaded chat
     * session do not match yet because those frames still reflect the previously displayed session.
     *
     * @param selectedSessionId Session selected in the UI.
     * @param activeChatSessionId Session currently loaded in the chat view model.
     * @param isSessionLoaded Whether the active chat session has finished loading.
     * @param currentLeafMessageId Leaf of the branch currently displayed for the active session.
     */
    private fun updateObservedCurrentLeaf(
        selectedSessionId: Long?,
        activeChatSessionId: Long?,
        isSessionLoaded: Boolean,
        currentLeafMessageId: Long?,
    ) {
        if (selectedSessionId != null && selectedSessionId == activeChatSessionId && isSessionLoaded) {
            observedCurrentLeafMessageId = currentLeafMessageId
        }
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
     * Advances the opaque trigger observed by the screen whenever a staged action needs reconciliation.
     */
    private fun advanceReconciliationVersion() {
        _reconciliationVersion.update { version -> version + 1 }
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

    /**
     * Stores a rollback target when a search result actually forces the user away from the previously
     * displayed branch.
     *
     * @param previousSessionId Session that was displayed before the branch switch request.
     * @param previousLeafMessageId Leaf of that previously displayed branch.
     * @param rollbackContextSessionId Session in which the rollback affordance should be offered.
     */
    private fun captureRollbackTarget(
        previousSessionId: Long?,
        previousLeafMessageId: Long?,
        rollbackContextSessionId: Long,
    ) {
        if (previousSessionId == null || previousLeafMessageId == null) {
            return
        }

        updateUiState { state ->
            state.copy(
                rollbackTarget = PreviousThreadRollbackTarget(
                    sessionId = previousSessionId,
                    leafMessageId = previousLeafMessageId,
                ),
                rollbackContextSessionId = rollbackContextSessionId,
            )
        }
    }

    /**
     * Removes the rollback affordance after the previous thread has been restored.
     */
    private fun clearRollbackTarget() {
        updateUiState { state ->
            state.copy(
                rollbackTarget = null,
                rollbackContextSessionId = null,
            )
        }
    }
}

/**
 * Tracks the staged steps required to turn a cross-session search hit into in-session navigation.
 *
 * @property sessionId Session that should be opened.
 * @property messageId Message that should become visible and selected.
 * @property query Query that should be injected into the in-session search UI.
 * @property previousSessionId Session that was displayed when the navigation started.
 * @property previousLeafMessageId Leaf of the branch that was displayed when the navigation started.
 * @property branchSwitchRequested Whether a branch switch was already requested for this hit.
 * @property searchActivated Whether the in-session search UI was already primed with [query].
 */
private data class PendingCrossSessionNavigation(
    val sessionId: Long,
    val messageId: Long,
    val query: String,
    val previousSessionId: Long?,
    val previousLeafMessageId: Long?,
    val branchSwitchRequested: Boolean = false,
    val searchActivated: Boolean = false,
)

/**
 * Tracks a deferred rollback request while the original session is being reopened and reconciled.
 *
 * @property sessionId Session that should be restored.
 * @property leafMessageId Leaf that anchors the exact previous thread.
 * @property branchSwitchRequested Whether the branch restore request was already issued.
 */
private data class PendingPreviousThreadReturn(
    val sessionId: Long,
    val leafMessageId: Long,
    val branchSwitchRequested: Boolean = false,
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
