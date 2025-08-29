package eu.torvian.chatbot.app.compose.preview

import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.compose.sessionlist.SessionListPanel
import eu.torvian.chatbot.app.domain.contracts.SessionListState
import eu.torvian.chatbot.app.domain.contracts.SessionListActions
import eu.torvian.chatbot.app.domain.contracts.SessionListData
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.common.models.ChatSessionSummary
import kotlinx.datetime.Instant
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview
@Composable
fun SessionListPanelPreview() {
    // Mock data for preview
    val mockState = SessionListState(
        listUiState = UiState.Success(
            SessionListData(
                allSessions = listOf(
                    ChatSessionSummary(
                        id = 1L,
                        name = "Session 1",
                        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
                        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
                        groupId = null,
                        groupName = null
                    )
                ),
                allGroups = emptyList()
            )
        ),
        selectedSessionId = 1L,
        isCreatingNewGroup = true
    )
    val mockActions = object : SessionListActions {
        override fun onSessionSelected(sessionId: Long?) {}
        override fun onCreateNewSession(name: String?) {}
        override fun onRenameSession(session: ChatSessionSummary, newName: String) {}
        override fun onDeleteSession(sessionId: Long) {}
        override fun onAssignSessionToGroup(sessionId: Long, groupId: Long?) {}
        override fun onStartCreatingNewGroup() {}
        override fun onUpdateNewGroupNameInput(newText: String) {}
        override fun onCreateNewGroup() {}
        override fun onCancelCreatingNewGroup() {}
        override fun onStartRenamingGroup(group: ChatGroup) {}
        override fun onUpdateEditingGroupNameInput(newText: String) {}
        override fun onSaveRenamedGroup() {}
        override fun onCancelRenamingGroup() {}
        override fun onDeleteGroup(groupId: Long) {}
        override fun onRetryLoadingSessions() {}
    }

    SessionListPanel(
        state = mockState,
        actions = mockActions
    )
}
