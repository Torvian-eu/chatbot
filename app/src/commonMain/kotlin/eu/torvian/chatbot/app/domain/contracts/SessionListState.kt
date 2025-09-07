package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.ChatGroup

/**
 * Encapsulates all UI state relevant to the Session List Panel.
 *
 * @property listUiState The state of the chat session list and groups.
 * @property selectedSessionId The ID of the currently selected session.
 * @property isCreatingNewGroup UI state indicating if the new group input field is visible.
 * @property newGroupNameInput Content of the new group input field.
 * @property editingGroup The group currently being edited/renamed. Null if none.
 * @property editingGroupNameInput Content of the editing group name input field.
 */
data class SessionListState(
    val listUiState: DataState<RepositoryError, SessionListData> = DataState.Idle,
    val selectedSessionId: Long? = null,
    val isCreatingNewGroup: Boolean = false,
    val newGroupNameInput: String = "",
    val editingGroup: ChatGroup? = null,
    val editingGroupNameInput: String = ""
)