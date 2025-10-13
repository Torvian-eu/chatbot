package eu.torvian.chatbot.app.compose.admin.usergroups

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.viewmodel.admin.UserGroupManagementDialogState
import eu.torvian.chatbot.common.models.user.UserGroup

/**
 * User group management tab with master-detail layout.
 *
 * Displays a list of user groups on the left and detailed information about the selected
 * group on the right. Provides actions for creating, editing, deleting groups, and
 * managing group membership.
 *
 * Handles different data states (Loading, Error, Success, Idle) and renders
 * dialogs for user interactions.
 *
 * @param groupsDataState The current state of group data (Loading, Error, Success, Idle)
 * @param selectedGroup The currently selected group, or null if none selected
 * @param dialogState The current dialog state (which dialog to show, if any)
 * @param actions Interface for handling user interactions
 * @param modifier Modifier for styling and layout
 */
@Composable
fun UserGroupManagementTab(
    groupsDataState: DataState<RepositoryError, List<UserGroup>>,
    selectedGroup: UserGroup?,
    dialogState: UserGroupManagementDialogState,
    actions: GroupManagementActions,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (groupsDataState) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading user groups...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load user groups",
                    error = groupsDataState.error,
                    onRetry = { actions.onLoadGroups() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val groups = groupsDataState.data

                Row(modifier = Modifier.fillMaxSize()) {
                    // Master: Group List
                    UserGroupListPanel(
                        groups = groups,
                        selectedGroup = selectedGroup,
                        onGroupSelected = { actions.onSelectGroup(it) },
                        onCreateGroup = { actions.onStartCreatingGroup() },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    // Detail: Group Details
                    UserGroupDetailPanel(
                        group = selectedGroup,
                        onEditGroup = { actions.onStartEditingGroup(it) },
                        onDeleteGroup = { actions.onStartDeletingGroup(it) },
                        onManageMembers = { actions.onStartManagingMembers(it) },
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
                            text = "User Group Management",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Button(onClick = { actions.onLoadGroups() }) {
                            Text("Load User Groups")
                        }
                    }
                }

                // Auto-load on first render
                LaunchedEffect(Unit) {
                    actions.onLoadGroups()
                }
            }
        }

        // Render dialogs on top
        GroupManagementDialogs(
            dialogState = dialogState,
            actions = actions
        )
    }
}

