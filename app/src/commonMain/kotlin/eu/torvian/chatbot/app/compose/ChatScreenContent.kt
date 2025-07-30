package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.LoadingOverlay
import eu.torvian.chatbot.app.viewmodel.SessionListViewModel
import eu.torvian.chatbot.app.viewmodel.UiState
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.ChatSession

/**
 * Composable for the main chat interface's content, including the session list and the chat area.
 * This composable is now stateless, receiving all necessary data and callbacks via parameters.
 * (Part of E7.S2: Implement Base App Layout & ViewModel Integration - with State Hoisting)
 *
 * TODO: In PRs 19, 20, 21, more parameters (e.g., chat input, messages) will be added here
 * to pass down specific chat-related data and actions.
 *
 * @param sessionListUiState The current UI state of the session list.
 * @param selectedSessionId The ID of the currently selected session.
 * @param onSessionSelected Callback triggered when a session is selected from the list.
 * @param chatUiState The current UI state of the active chat session.
 * @param showLoading Whether to display the loading overlay.
 */

@Composable
fun ChatScreenContent(
    sessionListUiState: UiState<ApiError, SessionListViewModel.SessionListData>,
    selectedSessionId: Long?,
    onSessionSelected: (Long) -> Unit,
    chatUiState: UiState<ApiError, ChatSession>,
    showLoading: Boolean
) {
    // Wrap the Row in a Box to allow stacking the overlay on top
    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(0.25f) // Fixed weight for Session List Panel
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                // PR 19 will fill this with actual Session List UI,
                // consuming sessionListUiState and calling onSessionSelected.
                Text(
                    "Session List Panel\n(PR 19 will consume sessionListUiState and call onSessionSelected)\n" +
                            "Current state: ${sessionListUiState::class.simpleName}\n" +
                            "Selected ID: ${selectedSessionId ?: "None"}",
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Box(
                modifier = Modifier
                    .weight(0.75f) // Fixed weight for Chat Area
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                // PR 20 & 21 will fill this with actual Chat Area UI.
                // It will receive chat-specific states and actions via hoisted parameters.
                Text(
                    "Chat Area\n(PRs 20, 21, etc. will consume chatUiState and other parameters)\n" +
                            "Current chat state: ${chatUiState::class.simpleName}",
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // Display the loading overlay over the entire content of ChatScreenContent
        if (showLoading) {
            LoadingOverlay(modifier = Modifier.fillMaxSize())
        }
    }
}