package eu.torvian.chatbot.app.compose.preview

import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.compose.sessionlist.SessionListActions
import eu.torvian.chatbot.app.compose.sessionlist.SessionListPanel
import eu.torvian.chatbot.app.compose.sessionlist.SessionListState
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.SessionListData
import eu.torvian.chatbot.app.viewmodel.CrossSessionSearchUiState
import eu.torvian.chatbot.app.viewmodel.sessionstatus.SessionIndicator
import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import androidx.compose.ui.tooling.preview.Preview
import kotlin.time.Instant

@Preview
@Composable
fun SessionListPanelPreview() {
    // Mock data for preview: one row per indicator state plus an idle row, so the trailing status
    // slot is exercised both with and without an icon.
    val mockState = SessionListState(
        listUiState = DataState.Success(
            SessionListData(
                allSessions = listOf(
                    ChatSessionSummary(
                        id = 1L,
                        name = "Selected session",
                        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
                        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
                        groupId = null,
                        groupName = null
                    ),
                    ChatSessionSummary(
                        id = 2L,
                        name = "Awaiting approval",
                        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
                        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
                        groupId = null,
                        groupName = null
                    ),
                    ChatSessionSummary(
                        id = 3L,
                        name = "Task completed",
                        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
                        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
                        groupId = null,
                        groupName = null
                    ),
                    ChatSessionSummary(
                        id = 4L,
                        name = "Task failed",
                        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
                        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
                        groupId = null,
                        groupName = null
                    ),
                    ChatSessionSummary(
                        id = 5L,
                        name = "Idle session",
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
        sessionIndicators = mapOf(
            1L to SessionIndicator.BUSY,
            2L to SessionIndicator.REQUESTING_INPUT,
            3L to SessionIndicator.COMPLETED_SUCCESS,
            4L to SessionIndicator.COMPLETED_FAILURE
        ),
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
        override fun onSearchClick() {}
        override fun onShowNewSessionDialog() {}
        override fun onShowRenameSessionDialog(session: ChatSessionSummary) {}
        override fun onShowDeleteSessionDialog(sessionId: Long) {}
        override fun onShowCloneSessionDialog(session: ChatSessionSummary) {}
        override fun onShowAssignGroupDialog(session: ChatSessionSummary) {}
        override fun onShowDeleteGroupDialog(groupId: Long) {}
    }

    SessionListPanel(
        state = mockState,
        actions = mockActions,
        crossSessionSearchState = CrossSessionSearchUiState(),
        onDismissSearchDialog = {},
        onUpdateSearchQuery = {},
        onUpdateSearchScope = {},
        onPerformSearch = {},
        onSearchResultClick = {},
    )
}
