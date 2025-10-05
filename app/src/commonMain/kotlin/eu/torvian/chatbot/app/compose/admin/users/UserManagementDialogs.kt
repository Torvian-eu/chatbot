package eu.torvian.chatbot.app.compose.admin.users

import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.viewmodel.admin.UserManagementDialogState

/**
 * Container for all user management dialogs.
 *
 * Renders the appropriate dialog based on the current dialog state.
 * This centralizes dialog management and ensures only one dialog is shown at a time.
 *
 * @param dialogState The current dialog state indicating which dialog to show
 * @param actions The actions interface for handling user interactions
 */
@Composable
fun UserManagementDialogs(
    dialogState: UserManagementDialogState,
    actions: UserManagementActions
) {
    when (dialogState) {
        is UserManagementDialogState.EditUser -> {
            EditUserDialog(
                user = dialogState.user,
                formState = dialogState.formState,
                onDismiss = { actions.onCancelDialog() },
                onConfirm = { actions.onSubmitEditUser() },
                onUsernameChange = { actions.onUpdateEditUserForm(username = it) },
                onEmailChange = { actions.onUpdateEditUserForm(email = it) }
            )
        }

        is UserManagementDialogState.DeleteUser -> {
            DeleteUserDialog(
                user = dialogState.user,
                onDismiss = { actions.onCancelDialog() },
                onConfirm = { actions.onConfirmDeleteUser() }
            )
        }

        is UserManagementDialogState.ManageRoles -> {
            ManageRolesDialog(
                user = dialogState.user,
                availableRoles = dialogState.availableRoles,
                isLoading = dialogState.isLoading,
                onDismiss = { actions.onCancelDialog() },
                onAssignRole = { actions.onAssignRole(it) },
                onRevokeRole = { actions.onRevokeRole(it) }
            )
        }

        is UserManagementDialogState.ChangePassword -> {
            ChangePasswordDialog(
                user = dialogState.user,
                formState = dialogState.formState,
                onDismiss = { actions.onCancelDialog() },
                onConfirm = { actions.onSubmitPasswordChange() },
                onNewPasswordChange = { actions.onUpdatePasswordForm(newPassword = it) },
                onConfirmPasswordChange = { actions.onUpdatePasswordForm(confirmPassword = it) }
            )
        }

        is UserManagementDialogState.ChangeUserStatus -> {
            ChangeUserStatusDialog(
                user = dialogState.user,
                isLoading = dialogState.isLoading,
                onDismiss = { actions.onCancelDialog() },
                onConfirm = { actions.onSubmitUserStatusChange(it) }
            )
        }

        is UserManagementDialogState.ChangePasswordChangeRequired -> {
            ChangePasswordChangeRequiredDialog(
                user = dialogState.user,
                isLoading = dialogState.isLoading,
                onDismiss = { actions.onCancelDialog() },
                onConfirm = { actions.onSubmitPasswordChangeRequiredChange(it) }
            )
        }

        UserManagementDialogState.None -> {
            // No dialog to show
        }
    }
}
