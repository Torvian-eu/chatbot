package eu.torvian.chatbot.app.compose

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
import eu.torvian.chatbot.app.domain.contracts.ChatAreaActions
import eu.torvian.chatbot.app.domain.contracts.ChatAreaState
import eu.torvian.chatbot.app.domain.contracts.SessionListActions
import eu.torvian.chatbot.app.domain.contracts.SessionListState

/**
 * Composable for the main chat interface's content, including the session list and the chat area.
 * This composable is now stateless, receiving all necessary data and callbacks via parameters.
 * (Part of E7.S2: Implement Base App Layout & ViewModel Integration - with State Hoisting)
 *
 * @param sessionListState The current UI state contract for the session list panel.
 * @param sessionListActions The actions contract for the session list panel.
 * @param chatAreaState The current UI state contract for the chat area.
 * @param chatAreaActions The actions contract for the chat area.
 */
@Composable
fun ChatScreenContent(
    sessionListState: SessionListState,
    sessionListActions: SessionListActions,
    chatAreaState: ChatAreaState,
    chatAreaActions: ChatAreaActions
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(0.25f) // Fixed weight for Session List Panel
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
        ) {
            // PR 19: Session List Panel
            SessionListPanel(
                state = sessionListState,
                actions = sessionListActions
            )
        }
        Box(
            modifier = Modifier
                .weight(0.75f) // Fixed weight for Chat Area
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            // PR 20: Implement Chat Area UI (Message Display)
            ChatArea(
                state = chatAreaState,
                actions = chatAreaActions
            )
        }
    }
}
