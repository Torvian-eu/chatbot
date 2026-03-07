package eu.torvian.chatbot.app.compose.preview

import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.compose.sessionlist.SessionListActions
import eu.torvian.chatbot.app.compose.sessionlist.SessionListPanel
import eu.torvian.chatbot.app.compose.sessionlist.SessionListState
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.SessionListData
import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import androidx.compose.ui.tooling.preview.Preview
import kotlin.time.Instant

@Preview
@Composable
fun SessionListPanelPreview() {
    // Mock data for preview
    val mockState = SessionListState(
        listUiState = DataState.Success(
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
        override fun onStartCreatingNewGroup() {}
        override fun onUpdateNewGroupNameInput(newText: String) {}
        override fun onCreateNewGroup() {}
        override fun onCancelCreatingNewGroup() {}
        override fun onStartRenamingGroup(group: ChatGroup) {}
        override fun onUpdateEditingGroupNameInput(newText: String) {}
        override fun onSaveRenamedGroup() {}
        override fun onCancelRenamingGroup() {}
        override fun onRetryLoadingSessions() {}
        override fun onShowNewSessionDialog() {}
        override fun onShowRenameSessionDialog(session: ChatSessionSummary) {}
        override fun onShowDeleteSessionDialog(sessionId: Long) {}
        override fun onShowCloneSessionDialog(session: ChatSessionSummary) {}
        override fun onShowAssignGroupDialog(session: ChatSessionSummary) {}
        override fun onShowDeleteGroupDialog(groupId: Long) {}
    }

    SessionListPanel(
        state = mockState,
        actions = mockActions
    )
}
