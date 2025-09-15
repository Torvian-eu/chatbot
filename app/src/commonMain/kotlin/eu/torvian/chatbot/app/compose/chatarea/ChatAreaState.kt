package eu.torvian.chatbot.app.compose.chatarea

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.ModelSettings

/**
 * Encapsulates all UI state relevant to the main Chat Area.
 *
 * @property sessionUiState The state of the currently loaded chat session.
 * @property availableModels The state of all available LLM models for selection.
 * @property availableSettingsForCurrentModel The state of settings profiles available for the current model.
 * @property currentModel The currently selected LLM model for the session.
 * @property currentSettings The currently selected model settings for the session.
 * @property modelsById A map of all available models indexed by their ID for quick lookup.
 * @property displayedMessages The list of messages to display in the UI, representing the currently selected thread branch.
 * @property inputContent The current text content in the message input field.
 * @property replyTargetMessage The message the user is currently explicitly replying to via the Reply action.
 * @property editingMessage The message currently being edited (E3.S1, E3.S2).
 * @property editingContent The content of the message currently being edited (E3.S1, E3.S2).
 * @property isSendingMessage Indicates whether a message is currently in the process of being sent (E1.S3).
 * @property dialogState The current dialog state for the chat area (e.g., delete confirmation).
 */
data class ChatAreaState(
    val sessionUiState: DataState<RepositoryError, ChatSession> = DataState.Idle,
    val availableModels: DataState<RepositoryError, List<LLMModel>> = DataState.Idle,
    val availableSettingsForCurrentModel: DataState<RepositoryError, List<ModelSettings>> = DataState.Idle,
    val currentModel: LLMModel? = null,
    val currentSettings: ModelSettings? = null,
    val modelsById: Map<Long, LLMModel> = emptyMap(),
    val displayedMessages: List<ChatMessage> = emptyList(),
    val inputContent: String = "",
    val replyTargetMessage: ChatMessage? = null,
    val editingMessage: ChatMessage? = null,
    val editingContent: String = "",
    val isSendingMessage: Boolean = false,
    val dialogState: ChatAreaDialogState = ChatAreaDialogState.None
)
