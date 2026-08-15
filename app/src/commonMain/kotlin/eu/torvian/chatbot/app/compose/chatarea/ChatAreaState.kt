package eu.torvian.chatbot.app.compose.chatarea

import eu.torvian.chatbot.app.chat.search.MessageSearchMatch
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.tool.ToolCall


/**
 * Encapsulates all UI state relevant to the main Chat Area.
 *
 * @property sessionUiState The state of the currently loaded chat session.
 * @property availableAgentRoles The state of all agent roles owned by the current user.
 * @property currentAgentRole The agent role currently selected for the session, or null when none is attached.
 * @property canSend Whether the composer is enabled: a resolvable agent role with a model and settings is required.
 * @property modelsById A map of all available models indexed by their ID for quick lookup.
 * @property displayedMessages The list of messages to display in the UI, representing the currently selected thread branch.
 * @property collapsedMessageIds Message IDs that should render in collapsed mode.
 * @property inputContent The current text content in the message input field.
 * @property replyTargetMessage The message the user is currently explicitly replying to via the Reply action.
 * @property editingMessage The message currently being edited (E3.S1, E3.S2).
 * @property editingContent The content of the message currently being edited (E3.S1, E3.S2).
 * @property editingFileReferences The list of file references being edited (E3.S2).
 * @property editingBasePathOverride The base path override being edited (E3.S2).
 * @property turnExecutionState Lifecycle state used by the composer action button.
 * @property dialogState The current dialog state for the chat area (e.g., delete confirmation).
 * @property toolCallsMap Tool calls for the current session, organized by message ID.
 * @property pendingFileReferences File references attached to the current message being composed.
 * @property searchQuery Current in-session search query.
 * @property searchResults Ordered occurrence-level matches for the current search query.
 * @property currentSearchIndex Currently selected search result index, or `-1` when no result is selected.
 * @property isSearchActive Whether the chat area is currently in search mode.
 */
data class ChatAreaState(
    val sessionUiState: DataState<RepositoryError, ChatSession> = DataState.Idle,
    val availableAgentRoles: DataState<RepositoryError, List<AgentRoleDto>> = DataState.Idle,
    val currentAgentRole: AgentRoleDto? = null,
    val canSend: Boolean = false,
    val modelsById: Map<Long, LLMModel> = emptyMap(),
    val displayedMessages: List<ChatMessage> = emptyList(),
    val collapsedMessageIds: Set<Long> = emptySet(),
    val inputContent: String = "",
    val replyTargetMessage: ChatMessage? = null,
    val editingMessage: ChatMessage? = null,
    val editingContent: String = "",
    val editingFileReferences: List<FileReference> = emptyList(),
    val editingBasePathOverride: String? = null,
    val turnExecutionState: TurnExecutionState = TurnExecutionState.IDLE,
    val dialogState: ChatAreaDialogState = ChatAreaDialogState.None,
    val toolCallsMap: Map<Long, List<ToolCall>> = emptyMap(),
    val pendingFileReferences: List<FileReference> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<MessageSearchMatch> = emptyList(),
    val currentSearchIndex: Int = -1,
    val isSearchActive: Boolean = false,
)
