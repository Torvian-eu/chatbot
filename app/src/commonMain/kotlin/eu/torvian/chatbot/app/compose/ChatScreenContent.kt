package eu.torvian.chatbot.app.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.chatarea.ChatArea
import eu.torvian.chatbot.app.compose.chatarea.ChatAreaActions
import eu.torvian.chatbot.app.compose.chatarea.ChatAreaState
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.compose.sessionlist.SessionListActions
import eu.torvian.chatbot.app.compose.sessionlist.SessionListPanel
import eu.torvian.chatbot.app.compose.sessionlist.SessionListState
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.api.core.MessageSearchScope

/**
 * Composable for the main chat interface's content, including the session list and the chat area.
 * This composable is now stateless, receiving all necessary data and callbacks via parameters.
 * (Part of E7.S2: Implement Base App Layout & ViewModel Integration - with State Hoisting)
 *
 * @param sessionListState The current UI state contract for the session list panel.
 * @param sessionListActions The actions contract for the session list panel.
 * @param chatAreaState The current UI state contract for the chat area.
 * @param chatAreaActions The actions contract for the chat area.
 * @param isSessionListCollapsed Whether the session list panel is collapsed.
 * @param isSearchDialogVisible Whether the cross-session search dialog is visible.
 * @param searchQuery Current editable query for cross-session search.
 * @param lastSearchQuery Query that produced the currently displayed cross-session results.
 * @param searchScope Scope currently selected in the dialog.
 * @param lastSearchScope Scope that produced the currently displayed search results.
 * @param searchResultsState Current search result state for the dialog.
 * @param onDismissSearchDialog Callback that closes the dialog.
 * @param onUpdateSearchQuery Callback for search query changes.
 * @param onUpdateSearchScope Callback for search scope changes.
 * @param onPerformSearch Callback that triggers a new cross-session search.
 * @param onSearchResultClick Callback invoked when a cross-session search result is selected.
 */
@Composable
fun ChatScreenContent(
    sessionListState: SessionListState,
    sessionListActions: SessionListActions,
    chatAreaState: ChatAreaState,
    chatAreaActions: ChatAreaActions,
    isSessionListCollapsed: Boolean,
    isSearchDialogVisible: Boolean,
    searchQuery: String,
    lastSearchQuery: String,
    searchScope: MessageSearchScope,
    lastSearchScope: MessageSearchScope,
    searchResultsState: DataState<RepositoryError, List<MessageSearchResult>>,
    onDismissSearchDialog: () -> Unit,
    onUpdateSearchQuery: (String) -> Unit,
    onUpdateSearchScope: (MessageSearchScope) -> Unit,
    onPerformSearch: () -> Unit,
    onSearchResultClick: (MessageSearchResult) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            modifier = Modifier.weight(0.25f), // Fixed weight for Session List Panel
            visible = !isSessionListCollapsed,
            enter = slideInHorizontally(),
            exit = slideOutHorizontally()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
            ) {
                // PR 19: Session List Panel
                SessionListPanel(
                    state = sessionListState,
                    actions = sessionListActions,
                    isSearchDialogVisible = isSearchDialogVisible,
                    searchQuery = searchQuery,
                    lastSearchQuery = lastSearchQuery,
                    searchScope = searchScope,
                    lastSearchScope = lastSearchScope,
                    searchResultsState = searchResultsState,
                    onDismissSearchDialog = onDismissSearchDialog,
                    onUpdateSearchQuery = onUpdateSearchQuery,
                    onUpdateSearchScope = onUpdateSearchScope,
                    onPerformSearch = onPerformSearch,
                    onSearchResultClick = onSearchResultClick,
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(0.75f) // Fixed weight for Chat Area
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(8.dp)
        ) {
            // PR 20: Implement Chat Area UI (Message Display)
            ChatArea(
                state = chatAreaState,
                actions = chatAreaActions
            )
        }
    }
}
