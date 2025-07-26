package eu.torvian.chatbot.app.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import eu.torvian.chatbot.app.viewmodel.ChatViewModel
import eu.torvian.chatbot.app.viewmodel.SessionListViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * A stateful wrapper Composable for the main chat interface.
 * This component is responsible for:
 * - Obtaining ViewModels via Koin.
 * - Collecting necessary state from these ViewModels.
 * - Managing specific ViewModel interactions for the chat feature.
 * - Passing the collected states and relevant event callbacks to the stateless [ChatScreenContent].
 * (E7.S2: Implementing the Stateful part of the Screen with internal ViewModel management)
 *
 * @param sessionListViewModel The ViewModel managing the session list state.
 * @param chatViewModel The ViewModel managing the active chat session state.
 */
@Composable
fun ChatScreen(
    sessionListViewModel: SessionListViewModel = koinViewModel(),
    chatViewModel: ChatViewModel = koinViewModel()
) {
    // Collect states from ViewModels relevant to this screen's content
    val sessionListUiState by sessionListViewModel.listState.collectAsState()
    val selectedSessionId by sessionListViewModel.selectedSessionId.collectAsState()
    val chatUiState by chatViewModel.sessionState.collectAsState()

    // Determine if the chat screen should show a loading overlay
    // This combines loading states from both sessionListViewModel (initial load)
    // and chatViewModel (loading specific session details).
    val showLoading = sessionListUiState.isLoading || chatUiState.isLoading

    // This interaction has been moved from AppShell to ChatScreen,
    // as it's directly related to this screen's functionality.
    LaunchedEffect(selectedSessionId) {
        selectedSessionId?.let { sessionId ->
            chatViewModel.loadSession(sessionId)
        } ?: chatViewModel.clearSession()
    }

    // Pass collected states and event callbacks down to the stateless content composable
    ChatScreenContent(
        sessionListUiState = sessionListUiState,
        selectedSessionId = selectedSessionId,
        onSessionSelected = { sessionId -> sessionListViewModel.selectSession(sessionId) },
        chatUiState = chatUiState,
        showLoading = showLoading
        // TODO: In future PRs, as ChatArea and SessionListPanel get built out,
        // pass more specific states (e.g., chatViewModel.inputContent, chatViewModel.displayedMessages)
        // and callbacks (e.g., chatViewModel.sendMessage, sessionListViewModel.createNewSession) here.
    )
}