package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.AgentRoleFormState
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.settings.AgentRolesViewModel
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Agent Roles settings category.
 *
 * The route keeps the ViewModel wiring and breadcrumb updates together so the visible page stays
 * separate from the underlying role-data selection. Selection state is owned by the
 * [AgentRolesViewModel]; this route only observes it to decide between the list and detail pages.
 *
 * @param authState Authentication context (currently unused by the role tab; roles are
 *   ownership-based per user).
 * @param viewModel Agent Roles ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param categoryResetSignal Incremented when the user re-selects this category in the sidebar;
 *   triggers a reset to the list view.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the current Agent Roles
 *   page in the breadcrumb trail.
 */
@Composable
fun AgentRolesTabRoute(
    authState: AuthState.Authenticated,
    modifier: Modifier = Modifier,
    viewModel: AgentRolesViewModel = koinViewModel(),
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    // Tab-local initial load of roles plus the model/settings/tool catalogs the form needs.
    LaunchedEffect(Unit) {
        viewModel.loadRolesAndCatalogs()
    }

    // Reset to list view when the category is re-selected in the sidebar.
    LaunchedEffect(categoryResetSignal) {
        if (categoryResetSignal > 0) {
            viewModel.selectRole(null)
        }
    }

    val rolesState by viewModel.rolesState.collectAsState()
    val selectedRole by viewModel.selectedRole.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()
    val modelsState by viewModel.modelsState.collectAsState()
    val settingsForFormModel by viewModel.settingsForFormModel.collectAsState()
    val toolsState by viewModel.toolsState.collectAsState()
    val modelsById by viewModel.modelsById.collectAsState()
    val settingsById by viewModel.settingsById.collectAsState()
    val toolsById by viewModel.toolsById.collectAsState()

    // If a role disappears while its detail page is open, fall back to the list page.
    val roles = rolesState.dataOrNull
    val selectedRoleForFallback = selectedRole
    LaunchedEffect(roles, selectedRoleForFallback) {
        if (roles != null && selectedRoleForFallback != null && roles.none { it.id == selectedRoleForFallback.id }) {
            viewModel.selectRole(null)
        }
    }

    val breadcrumbs = selectedRole?.let {
        listOf(
            "Settings",
            SettingsCategory.AgentRoles.displayLabel,
            it.displayName?.takeIf { name -> name.isNotBlank() } ?: it.name
        )
    } ?: listOf("Settings", SettingsCategory.AgentRoles.displayLabel)

    LaunchedEffect(breadcrumbs) {
        onBreadcrumbsChanged(breadcrumbs)
    }

    val state = AgentRolesTabState(
        rolesUiState = rolesState,
        selectedRole = selectedRole,
        dialogState = dialogState,
        models = modelsState.dataOrNull.orEmpty(),
        settingsForFormModel = settingsForFormModel,
        tools = toolsState.dataOrNull.orEmpty(),
        modelsById = modelsById,
        settingsById = settingsById,
        toolsById = toolsById
    )

    val actions = object : AgentRolesTabActions {
        override fun onLoadRolesAndCatalogs() = viewModel.loadRolesAndCatalogs()
        override fun onSelectRole(role: AgentRoleDto?) = viewModel.selectRole(role)
        override fun onStartAddingNewRole() = viewModel.startAddingNewRole()
        override fun onStartEditingRole(role: AgentRoleDto) = viewModel.startEditingRole(role)
        override fun onStartDeletingRole(role: AgentRoleDto) = viewModel.startDeletingRole(role)
        override fun onUpdateRoleForm(update: (AgentRoleFormState) -> AgentRoleFormState) =
            viewModel.updateRoleForm(update)
        override fun onSaveRole() = viewModel.saveRole()
        override fun onDeleteRole(roleId: Long) = viewModel.deleteRole(roleId)
        override fun onCancelDialog() = viewModel.cancelDialog()
    }

    AgentRolesTab(
        state = state,
        actions = actions,
        onOpenRoleDetails = { role -> viewModel.selectRole(role) },
        onBackToRoleList = { viewModel.selectRole(null) },
        modifier = modifier
    )
}
