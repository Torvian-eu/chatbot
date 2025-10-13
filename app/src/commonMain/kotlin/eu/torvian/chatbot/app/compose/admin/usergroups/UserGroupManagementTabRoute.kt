package eu.torvian.chatbot.app.compose.admin.usergroups

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.admin.UserGroupManagementViewModel
import org.koin.compose.koinInject

/**
 * Route component for User Group Management tab.
 *
 * Connects the ViewModel to the UI and manages state collection.
 * This component bridges the ViewModel layer and the UI presentation layer,
 * collecting state flows and mapping ViewModel methods to UI actions.
 *
 * @param authState The current authentication state (passed from AdminScreen)
 * @param viewModel The group management ViewModel (injected via Koin)
 */
@Composable
fun UserGroupManagementTabRoute(
    authState: AuthState.Authenticated,
    viewModel: UserGroupManagementViewModel = koinInject()
) {
    // Tab-local initial load
    LaunchedEffect(Unit) {
        viewModel.loadGroups()
    }

    // Collect state
    val groupsDataState by viewModel.groupsDataState.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()

    // Build actions object
    val actions = object : GroupManagementActions {
        override fun onLoadGroups() = viewModel.loadGroups()

        override fun onSelectGroup(group: eu.torvian.chatbot.common.models.user.UserGroup?) =
            viewModel.selectGroup(group)

        override fun onStartCreatingGroup() = viewModel.startCreatingGroup()

        override fun onUpdateGroupForm(name: String?, description: String?) {
            viewModel.updateGroupForm(name = name, description = description)
        }

        override fun onSubmitCreateGroup() = viewModel.submitCreateGroup()

        override fun onStartEditingGroup(group: eu.torvian.chatbot.common.models.user.UserGroup) =
            viewModel.startEditingGroup(group)

        override fun onSubmitEditGroup() = viewModel.submitEditGroup()

        override fun onStartDeletingGroup(group: eu.torvian.chatbot.common.models.user.UserGroup) =
            viewModel.startDeletingGroup(group)

        override fun onConfirmDeleteGroup() = viewModel.confirmDeleteGroup()

        override fun onStartManagingMembers(group: eu.torvian.chatbot.common.models.user.UserGroup) =
            viewModel.startManagingMembers(group)

        override fun onAddMemberToGroup(user: eu.torvian.chatbot.common.models.user.User) =
            viewModel.addMemberToGroup(user)

        override fun onRemoveMemberFromGroup(user: eu.torvian.chatbot.common.models.user.User) =
            viewModel.removeMemberFromGroup(user)

        override fun onCancelDialog() = viewModel.closeDialog()
    }

    UserGroupManagementTab(
        groupsDataState = groupsDataState,
        selectedGroup = selectedGroup,
        dialogState = dialogState,
        actions = actions
    )
}

