package eu.torvian.chatbot.app.compose.admin.users

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.viewmodel.admin.UserManagementDialogState
import eu.torvian.chatbot.common.models.UserWithDetails

/**
 * User management tab with master-detail layout.
 *
 * Displays a list of users on the left and detailed information about the selected
 * user on the right. Provides actions for editing, deleting, managing roles,
 * changing passwords, and changing user status.
 *
 * Handles different data states (Loading, Error, Success, Idle) and renders
 * dialogs for user interactions.
 *
 * @param usersDataState The current state of user data (Loading, Error, Success, Idle)
 * @param selectedUser The currently selected user, or null if none selected
 * @param dialogState The current dialog state (which dialog to show, if any)
 * @param actions Interface for handling user interactions
 * @param modifier Modifier for styling and layout
 */
@Composable
fun UserManagementTab(
    usersDataState: DataState<RepositoryError, List<UserWithDetails>>,
    selectedUser: UserWithDetails?,
    dialogState: UserManagementDialogState,
    actions: UserManagementActions,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (usersDataState) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading users...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load users",
                    error = usersDataState.error,
                    onRetry = { actions.onLoadUsers() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val users = usersDataState.data

                Row(modifier = Modifier.fillMaxSize()) {
                    // Master: User List
                    UserListPanel(
                        users = users,
                        selectedUser = selectedUser,
                        onUserSelected = { actions.onSelectUser(it) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    // Detail: User Details
                    UserDetailPanel(
                        user = selectedUser,
                        onEditUser = { actions.onStartEditingUser(it) },
                        onDeleteUser = { actions.onStartDeletingUser(it) },
                        onManageRoles = { actions.onStartManagingRoles(it) },
                        onChangePassword = { actions.onStartChangingPassword(it) },
                        onChangeStatus = { actions.onStartChangingUserStatus(it) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 16.dp)
                    )
                }
            }

            is DataState.Idle -> {
                // Idle state - trigger initial load
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "User Management",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Button(onClick = { actions.onLoadUsers() }) {
                            Text("Load Users")
                        }
                    }
                }

                // Auto-load on first render
                LaunchedEffect(Unit) {
                    actions.onLoadUsers()
                }
            }
        }

        // Render dialogs on top
        UserManagementDialogs(
            dialogState = dialogState,
            actions = actions
        )
    }
}
