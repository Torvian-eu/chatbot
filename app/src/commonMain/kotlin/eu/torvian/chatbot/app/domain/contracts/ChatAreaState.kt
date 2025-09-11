package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatSessionData
import eu.torvian.chatbot.common.models.ChatMessage

/**
 * Encapsulates all UI state relevant to the main Chat Area.
 * (To be fully implemented in future PRs like 20, 21, etc.)
 *
 * @property sessionUiState The state of the currently loaded chat session with its model settings.
 * @property displayedMessages The list of messages to display in the UI, representing the currently selected thread branch.
 * @property inputContent The current text content in the message input field.
 * @property replyTargetMessage The message the user is currently explicitly replying to via the Reply action.
 * @property editingMessage The message currently being edited (E3.S1, E3.S2).
 * @property editingContent The content of the message currently being edited (E3.S1, E3.S2).
 * @property isSendingMessage Indicates whether a message is currently in the process of being sent (E1.S3).
 * @property dialogState The current dialog state for the chat area (e.g., delete confirmation).
 */
data class ChatAreaState(
    val sessionUiState: DataState<RepositoryError, ChatSessionData> = DataState.Idle,
    val displayedMessages: List<ChatMessage> = emptyList(),
    val inputContent: String = "",
    val replyTargetMessage: ChatMessage? = null,
    val editingMessage: ChatMessage? = null,
    val editingContent: String = "",
    val isSendingMessage: Boolean = false,
    val dialogState: ChatAreaDialogState = ChatAreaDialogState.None

    // Will include model/settings selection states from ChatViewModel in future PRs
)