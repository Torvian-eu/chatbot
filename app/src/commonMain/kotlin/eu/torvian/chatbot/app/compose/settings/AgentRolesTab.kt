package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState

/**
 * Agent Roles management tab with separate list and detail pages.
 *
 * The tab stays presentational: it switches between list/detail while the route owns page
 * navigation state and the ViewModel owns dialogs and form state.
 *
 * @param state Current Agent Roles tab state from the route.
 * @param actions ViewModel-forwarding actions for role CRUD flows.
 * @param onOpenRoleDetails Callback invoked when the user opens a role detail page.
 * @param onBackToRoleList Callback invoked when the user returns to the role list.
 * @param modifier Modifier applied to the tab container.
 */
@Composable
fun AgentRolesTab(
    state: AgentRolesTabState,
    actions: AgentRolesTabActions,
    onOpenRoleDetails: (eu.torvian.chatbot.common.models.agent.AgentRoleDto) -> Unit,
    onBackToRoleList: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (val uiState = state.rolesUiState) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading agent roles...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load agent roles",
                    error = uiState.error,
                    onRetry = { actions.onLoadRolesAndCatalogs() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val roles = uiState.data
                val selectedRole = state.selectedRole

                if (selectedRole != null) {
                    AgentRoleDetailPage(
                        role = selectedRole,
                        modelsById = state.modelsById,
                        settingsById = state.settingsById,
                        toolsById = state.toolsById,
                        onBackToList = onBackToRoleList,
                        onEdit = { actions.onStartEditingRole(it) },
                        onDelete = { actions.onStartDeletingRole(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AgentRoleListPage(
                        roles = roles,
                        selectedRole = selectedRole,
                        onRoleSelected = { role -> onOpenRoleDetails(role) },
                        onAddNewRole = { actions.onStartAddingNewRole() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            is DataState.Idle -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Agent roles will appear here.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { actions.onLoadRolesAndCatalogs() }) {
                            Text("Load Agent Roles")
                        }
                    }
                }
            }
        }
    }

    AgentRoleDialogs(
        dialogState = state.dialogState,
        actions = actions,
        models = state.models,
        settingsForModel = state.settingsForFormModel,
        tools = state.tools,
        roles = state.rolesUiState.dataOrNull.orEmpty()
    )
}
