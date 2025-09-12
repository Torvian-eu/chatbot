package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingOverlay
import eu.torvian.chatbot.app.domain.contracts.ChatAreaDialogState
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSession

/**
 * Composable for the main chat message display area.
 * Handles displaying messages, loading/error states, and threading indicators.
 * (PR 20: Implement Chat Area UI (Message Display) (E1.S*))
 *
 * @param state The current UI state contract for the chat area.
 * @param actions The actions contract for the chat area.
 */
@Composable
fun ChatArea(
    state: ChatAreaState,
    actions: ChatAreaActions
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (state.sessionUiState) {
            DataState.Loading -> LoadingStateDisplay(modifier = Modifier.fillMaxSize())
            is DataState.Error -> ErrorStateDisplay(
                error = state.sessionUiState.error,
                onRetry = actions::onRetryLoadingSession,
                title = "Failed to load chat session",
                modifier = Modifier.align(Alignment.Center)
            )

            DataState.Idle -> IdleStateDisplay(modifier = Modifier.align(Alignment.Center))
            is DataState.Success -> SuccessStateDisplay(
                chatSession = state.sessionUiState.data.session,
                displayedMessages = state.displayedMessages,
                actions = actions,
                inputContent = state.inputContent,
                replyTargetMessage = state.replyTargetMessage,
                isSendingMessage = state.isSendingMessage,
                editingMessage = state.editingMessage,
                editingContent = state.editingContent,
                dialogState = state.dialogState
            )
        }
    }
}

/**
 * Displays a loading overlay.
 */
@Composable
private fun LoadingStateDisplay(modifier: Modifier = Modifier) {
    LoadingOverlay(modifier = modifier)
}

/**
 * Displays the idle state message when no session is selected.
 */
@Composable
private fun IdleStateDisplay(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Select a session from the left or create a new one.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Messages will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Displays the loaded chat session with messages.
 *
 * @param chatSession The current chat session data.
 * @param displayedMessages The list of messages to display.
 * @param actions The actions contract for the chat area, providing message-related callbacks.
 * @param inputContent The current text content in the message input field.
 * @param replyTargetMessage The message the user is currently explicitly replying to via the Reply action.
 * @param isSendingMessage Indicates whether a message is currently in the process of being sent.
 * @param editingMessage The message currently being edited (E3.S1, E3.S2).
 * @param editingContent The content of the message currently being edited (E3.S1, E3.S2).
 * @param dialogState The current dialog state from the ViewModel.
 */
@Composable
private fun SuccessStateDisplay(
    chatSession: ChatSession,
    displayedMessages: List<ChatMessage>,
    actions: ChatAreaActions,
    inputContent: String,
    replyTargetMessage: ChatMessage?,
    isSendingMessage: Boolean,
    editingMessage: ChatMessage?,
    editingContent: String?,
    dialogState: ChatAreaDialogState
) {
    // Prepare message actions to pass down
    val messageActions = remember(actions) {
        MessageActions(
            onSwitchBranchToMessage = actions::onSwitchBranchToMessage,
            onEditMessage = actions::onStartEditing,
            onReplyMessage = actions::onStartReplyTo,
            onDeleteMessage = actions::onRequestDeleteMessage,
            // Keep onCopyMessage as null until PR 25 - Copy to Clipboard implementation
            onCopyMessage = null,
            // TODO: Wire up regenerate action when available
            onRegenerateMessage = null
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MessageList(
            chatSession = chatSession,
            displayedMessages = displayedMessages,
            messageActions = messageActions,
            editingMessage = editingMessage,
            editingContent = editingContent,
            actions = actions,
            modifier = Modifier.weight(1f) // Messages take up most space
        )
        InputArea(
            inputContent = inputContent,
            onUpdateInput = actions::onUpdateInput,
            onSendMessage = actions::onSendMessage,
            replyTargetMessage = replyTargetMessage,
            onCancelReply = actions::onCancelReply,
            isSendingMessage = isSendingMessage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp) // Small padding between messages and input
        )
    }

    Dialogs(
        dialogState = dialogState
    )
}