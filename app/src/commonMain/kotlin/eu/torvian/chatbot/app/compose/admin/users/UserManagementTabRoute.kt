package eu.torvian.chatbot.app.compose.admin.users

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.admin.UserManagementViewModel
import eu.torvian.chatbot.common.models.user.Role
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.models.user.UserWithDetails
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route component for User Management tab.
 *
 * Connects the ViewModel to the UI and manages state collection.
 * This component bridges the ViewModel layer and the UI presentation layer,
 * collecting state flows and mapping ViewModel methods to UI actions.
 *
 * @param authState The current authentication state (passed from AdminScreen)
 */
@Composable
fun UserManagementTabRoute(
    authState: AuthState.Authenticated,
    viewModel: UserManagementViewModel = koinViewModel()
) {
    // Tab-local initial load
    LaunchedEffect(Unit) {
        viewModel.loadUsers()
    }

    // Collect state
    val usersDataState by viewModel.usersDataState.collectAsState()
    val selectedUser by viewModel.selectedUser.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()

    // Build actions object
    val actions = object : UserManagementActions {
        override fun onLoadUsers() = viewModel.loadUsers()

        override fun onSelectUser(user: UserWithDetails?) = viewModel.selectUser(user)

        override fun onStartEditingUser(user: UserWithDetails) = viewModel.startEditingUser(user)
        override fun onUpdateEditUserForm(username: String?, email: String?) {
            viewModel.updateEditUserForm(username = username, email = email)
        }

        override fun onSubmitEditUser() = viewModel.submitEditUser()

        override fun onStartDeletingUser(user: UserWithDetails) = viewModel.startDeletingUser(user)
        override fun onConfirmDeleteUser() = viewModel.confirmDeleteUser()

        override fun onStartManagingRoles(user: UserWithDetails) = viewModel.startManagingRoles(user)
        override fun onAssignRole(role: Role) = viewModel.assignRole(role)
        override fun onRevokeRole(role: Role) = viewModel.revokeRole(role)

        override fun onStartChangingPassword(user: UserWithDetails) = viewModel.startChangingPassword(user)
        override fun onUpdatePasswordForm(newPassword: String?, confirmPassword: String?) {
            viewModel.updatePasswordForm(newPassword = newPassword, confirmPassword = confirmPassword)
        }

        override fun onSubmitPasswordChange() = viewModel.submitPasswordChange()

        override fun onStartChangingUserStatus(user: UserWithDetails) = viewModel.startChangingUserStatus(user)
        override fun onSubmitUserStatusChange(status: UserStatus) = viewModel.submitUserStatusChange(status)

        override fun onStartChangingPasswordChangeRequired(user: UserWithDetails) =
            viewModel.startChangingPasswordChangeRequired(user)
        override fun onSubmitPasswordChangeRequiredChange(requiresPasswordChange: Boolean) =
            viewModel.submitPasswordChangeRequiredChange(requiresPasswordChange)

        override fun onCancelDialog() = viewModel.closeDialog()
    }

    UserManagementTab(
        usersDataState = usersDataState,
        selectedUser = selectedUser,
        dialogState = dialogState,
        actions = actions
    )
}
