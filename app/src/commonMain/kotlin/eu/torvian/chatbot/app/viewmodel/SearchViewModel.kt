package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SearchRepository
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
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
 * The dialog visibility is intentionally decoupled from the repository-backed result state so
 * closing the dialog does not discard previously loaded results.
 *
 * @property searchRepository Repository executing and caching search requests.
 * @property notificationService Service used to surface validation and repository failures.
 * @property uiDispatcher Dispatcher used for UI-triggered coroutine launches.
 */
class SearchViewModel(
    private val searchRepository: SearchRepository,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ViewModel() {

    /**
     * Backing visibility state for the search dialog.
     */
    private val _isSearchDialogVisible = MutableStateFlow(false)

    /**
     * Backing state for the editable search query input.
     */
    private val _searchQuery = MutableStateFlow("")

    /**
     * Backing state for the query that produced the currently displayed result set.
     */
    private val _lastSearchQuery = MutableStateFlow("")

    /**
     * Indicates whether the search dialog should currently be shown.
     */
    val isSearchDialogVisible: StateFlow<Boolean> = _isSearchDialogVisible.asStateFlow()

    /**
     * Current text shown in the dialog's query field.
     */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Query string that produced the current repository-backed search results.
     */
    val lastSearchQuery: StateFlow<String> = _lastSearchQuery.asStateFlow()

    /**
     * Latest repository-backed search state shared with the dialog UI.
     */
    val searchResults: StateFlow<DataState<RepositoryError, List<MessageSearchResult>>> =
        searchRepository.searchResults

    /**
     * Opens the search dialog without altering any cached results.
     */
    fun showSearchDialog() {
        _isSearchDialogVisible.value = true
    }

    /**
     * Closes the search dialog without clearing the previous query or results.
     */
    fun hideSearchDialog() {
        _isSearchDialogVisible.value = false
    }

    /**
     * Updates the editable query text shown in the dialog.
     *
     * @param query New text entered by the user.
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Executes a new cross-session search using the current query text.
     *
     * Blank input is rejected locally so an accidental dialog submit does not wipe out the
     * previous results.
     */
    fun performSearch() {
        val normalizedQuery = searchQuery.value.trim()
        if (normalizedQuery.isBlank()) {
            viewModelScope.launch(uiDispatcher) {
                notificationService.genericWarning("Enter a search query.")
            }
            return
        }

        _searchQuery.value = normalizedQuery
        _lastSearchQuery.value = normalizedQuery

        viewModelScope.launch(uiDispatcher) {
            searchRepository.searchMessages(normalizedQuery).fold(
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
        _searchQuery.update { "" }
        _lastSearchQuery.update { "" }
        searchRepository.clearSearch()
    }
}