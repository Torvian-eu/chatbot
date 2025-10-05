package eu.torvian.chatbot.app.compose.sessionlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingOverlay
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.SessionListData
import eu.torvian.chatbot.app.domain.contracts.SessionListDialogState
import eu.torvian.chatbot.common.models.core.ChatGroup

/**
 * Stateless Composable for the session list panel.
 * This component is responsible for:
 * - Displaying the list of chat sessions and groups based on `DataState`.
 * - Delegating to `SessionListSuccessPanelContent` when data is available.
 * - Displaying loading, error, or idle states.
 *
 * @param state The current state contract for the session list panel.
 * @param actions The actions contract for the session list panel.
 */
@Composable
fun SessionListPanel(
    state: SessionListState,
    actions: SessionListActions
) {
    when (val listUiState = state.listUiState) {
        DataState.Loading -> {
            LoadingOverlay(Modifier.fillMaxSize())
        }

        is DataState.Error -> {
            ErrorStateDisplay(
                error = listUiState.error,
                onRetry = actions::onRetryLoadingSessions,
                title = "Failed to load sessions",
                modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center)
            )
        }

        DataState.Idle -> { // Should not happen, but just in case
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No sessions loaded.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        is DataState.Success -> {
            val sessionListData = listUiState.data
            SessionListSuccessPanelContent(
                sessionListData = sessionListData,
                isCreatingNewGroup = state.isCreatingNewGroup,
                newGroupNameInput = state.newGroupNameInput,
                editingGroup = state.editingGroup,
                editingGroupNameInput = state.editingGroupNameInput,
                selectedSessionId = state.selectedSessionId,
                dialogState = state.dialogState,
                sessionListActions = actions
            )
        }
    }
}

/**
 * Composable that displays the main content of the SessionListPanel
 * when the Data state is `DataState.Success`.
 * This includes the header, new group input, session list, and dialogs.
 *
 * @param sessionListData The successfully loaded session and group data (kept for allSessions/allGroups for dialogs).
 * @param isCreatingNewGroup Whether a new group input field is visible.
 * @param newGroupNameInput The current input for new group name.
 * @param editingGroup The group being edited, if any.
 * @param editingGroupNameInput The current input for editing group name.
 * @param selectedSessionId The ID of the currently selected session.
 * @param dialogState The current dialog state from the ViewModel.
 * @param sessionListActions The actions contract for the session list panel.
 */
@Composable
private fun SessionListSuccessPanelContent(
    sessionListData: SessionListData,
    isCreatingNewGroup: Boolean,
    newGroupNameInput: String,
    editingGroup: ChatGroup?,
    editingGroupNameInput: String,
    selectedSessionId: Long?,
    dialogState: SessionListDialogState,
    sessionListActions: SessionListActions
) {

    // Group editing actions
    val groupEditingActions = remember(sessionListActions) {
        GroupEditingActions(
            onUpdateEditingGroupNameInput = sessionListActions::onUpdateEditingGroupNameInput,
            onSaveRenamedGroup = sessionListActions::onSaveRenamedGroup,
            onCancelRenamingGroup = sessionListActions::onCancelRenamingGroup,
            onStartRenamingGroup = sessionListActions::onStartRenamingGroup
        )
    }

    // Dialog request actions
    val dialogRequestActions = remember(sessionListActions) {
        DialogActions(
            onRenameSessionRequested = sessionListActions::onShowRenameSessionDialog,
            onDeleteSessionRequested = sessionListActions::onShowDeleteSessionDialog,
            onAssignToGroupRequested = sessionListActions::onShowAssignGroupDialog,
            onDeleteGroupRequested = sessionListActions::onShowDeleteGroupDialog
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SessionListHeader(
            onNewSessionClick = sessionListActions::onShowNewSessionDialog,
            onNewGroupClick = sessionListActions::onStartCreatingNewGroup
        )
        // --- New Group Input (E6.S3) ---
        NewGroupInputSection(
            isVisible = isCreatingNewGroup,
            groupNameInput = newGroupNameInput,
            onGroupNameChange = sessionListActions::onUpdateNewGroupNameInput,
            onCreateGroup = sessionListActions::onCreateNewGroup,
            onCancelCreation = sessionListActions::onCancelCreatingNewGroup
        )
        // --- Main Content: Session List (E2.S3, E6.S2) ---
        MainContent(
            groupedSessions = sessionListData.groupedSessions,
            selectedSessionId = selectedSessionId,
            editingGroup = editingGroup,
            editingGroupNameInput = editingGroupNameInput,
            onSessionSelected = sessionListActions::onSessionSelected,
            groupEditingActions = groupEditingActions,
            dialogRequestActions = dialogRequestActions
        )
    }
    // --- Dialogs (E2.S4, E6.S4) ---
    Dialogs(
        dialogState = dialogState,
        allSessions = sessionListData.allSessions,
        allGroups = sessionListData.allGroups
    )
}