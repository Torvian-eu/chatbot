package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.SearchRepository
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.api.core.MessageSearchScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing the cross-session search dialog state and actions.
 *
 * The ViewModel exposes one cohesive UI state object while still keeping repository-backed results
 * independent from dialog visibility so closing the dialog does not discard previously loaded data.
 *
 * @property searchRepository Repository executing and caching search requests.
 * @property searchNavigationCoordinator Coordinator for cross-session search navigation.
 * @property notificationService Service used to surface validation and repository failures.
 * @property uiDispatcher Dispatcher used for UI-triggered coroutine launches.
 */
class CrossSessionSearchViewModel(
    private val searchRepository: SearchRepository,
    private val searchNavigationCoordinator: SearchNavigationCoordinator,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ViewModel() {

    /**
     * Backing state for the entire cross-session search dialog.
     */
    private val _uiState = MutableStateFlow(
        CrossSessionSearchUiState(
            resultsState = searchRepository.searchResults.value,
        )
    )

    /**
     * Single state object consumed by the cross-session search UI.
     */
    val uiState: StateFlow<CrossSessionSearchUiState> = _uiState.asStateFlow()

    init {
        // Repository results stay the source of truth so close/reopen behavior continues to preserve
        // the last completed response independently from dialog visibility.
        viewModelScope.launch(uiDispatcher) {
            searchRepository.searchResults.collect { resultsState ->
                _uiState.update { state ->
                    state.copy(resultsState = resultsState)
                }
            }
        }
    }

    /**
     * Opens the search dialog without altering any cached results.
     */
    fun showSearchDialog() {
        _uiState.update { state -> state.copy(isDialogVisible = true) }
    }

    /**
     * Closes the search dialog without clearing the previous query or results.
     */
    fun hideSearchDialog() {
        _uiState.update { state -> state.copy(isDialogVisible = false) }
    }

    /**
     * Updates the editable query text shown in the dialog.
     *
     * @param query New text entered by the user.
     */
    fun updateSearchQuery(query: String) {
        _uiState.update { state -> state.copy(draftQuery = query) }
    }

    /**
     * Updates the search scope selected in the dialog.
     *
     * @param scope Newly selected server-side search scope.
     */
    fun updateSearchScope(scope: MessageSearchScope) {
        _uiState.update { state -> state.copy(draftScope = scope) }
    }

    /**
     * Executes a new cross-session search using the current query text.
     *
     * Blank input is rejected locally so an accidental dialog submit does not wipe out the
     * previous results.
     */
    fun performSearch() {
        val state = uiState.value
        val normalizedQuery = state.draftQuery.trim()
        val selectedScope = state.draftScope
        if (normalizedQuery.isBlank()) {
            viewModelScope.launch(uiDispatcher) {
                notificationService.genericWarning("Enter a search query.")
            }
            return
        }

        _uiState.update { currentState ->
            currentState.copy(
                draftQuery = normalizedQuery,
                submittedQuery = normalizedQuery,
                submittedScope = selectedScope,
            )
        }

        viewModelScope.launch(uiDispatcher) {
            searchRepository.searchMessages(normalizedQuery, selectedScope).fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to search messages"
                    )
                },
                ifRight = {}
            )
        }
    }

    /**
     * Clears the editable query and cached result state.
     */
    fun clearSearch() {
        _uiState.update { state ->
            state.copy(
                draftQuery = "",
                submittedQuery = "",
                submittedScope = state.draftScope,
            )
        }
        searchRepository.clearSearch()
    }

    /**
     * Handles a cross-session search result click by initiating navigation.
     *
     * This method calls [SearchNavigationCoordinator.startNavigation] with the navigation query
     * and closes the dialog. The [ChatViewModel] will observe the navigation intent and
     * perform branch switching and search activation when the session is loaded and active.
     *
     * @param result The search result that was clicked.
     */
    fun onSearchResultClick(result: MessageSearchResult) {
        viewModelScope.launch(uiDispatcher) {
            // Initiate navigation through coordinator
            searchNavigationCoordinator.startNavigation(
                sessionId = result.sessionId,
                messageId = result.messageId,
                query = uiState.value.navigationQuery,
            )
            // Dismiss dialog
            hideSearchDialog()
        }
    }
}
