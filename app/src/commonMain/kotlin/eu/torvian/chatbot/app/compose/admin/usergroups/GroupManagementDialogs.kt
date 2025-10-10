package eu.torvian.chatbot.app.compose.admin.usergroups

import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.viewmodel.admin.UserGroupManagementDialogState

/**
 * Container for all user group management dialogs.
 *
 * Renders the appropriate dialog based on the current dialog state.
 * This centralizes dialog management and ensures only one dialog is shown at a time.
 *
 * @param dialogState The current dialog state indicating which dialog to show
 * @param actions The actions interface for handling user interactions
 */
@Composable
fun GroupManagementDialogs(
    dialogState: UserGroupManagementDialogState,
    actions: GroupManagementActions
) {
    when (dialogState) {
        is UserGroupManagementDialogState.CreateGroup -> {
            CreateGroupDialog(
                formState = dialogState.formState,
                onDismiss = { actions.onCancelDialog() },
                onConfirm = { actions.onSubmitCreateGroup() },
                onNameChange = { actions.onUpdateGroupForm(name = it) },
                onDescriptionChange = { actions.onUpdateGroupForm(description = it) }
            )
        }

        is UserGroupManagementDialogState.EditGroup -> {
            EditGroupDialog(
                group = dialogState.group,
                formState = dialogState.formState,
                onDismiss = { actions.onCancelDialog() },
                onConfirm = { actions.onSubmitEditGroup() },
                onNameChange = { actions.onUpdateGroupForm(name = it) },
                onDescriptionChange = { actions.onUpdateGroupForm(description = it) }
            )
        }

        is UserGroupManagementDialogState.DeleteGroup -> {
            DeleteGroupDialog(
                group = dialogState.group,
                onDismiss = { actions.onCancelDialog() },
                onConfirm = { actions.onConfirmDeleteGroup() }
            )
        }

        is UserGroupManagementDialogState.ManageMembers -> {
            ManageGroupMembersDialog(
                group = dialogState.group,
                members = dialogState.members,
                availableUsers = dialogState.availableUsers,
                isLoading = dialogState.isLoading,
                onDismiss = { actions.onCancelDialog() },
                onAddMember = { actions.onAddMemberToGroup(it) },
                onRemoveMember = { actions.onRemoveMemberFromGroup(it) }
            )
        }

        UserGroupManagementDialogState.None -> {
            // No dialog to show
        }
    }
}

