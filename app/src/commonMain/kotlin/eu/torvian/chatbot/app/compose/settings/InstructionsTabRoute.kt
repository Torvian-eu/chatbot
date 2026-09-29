package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.AgentRoleFilter
import eu.torvian.chatbot.app.domain.contracts.InstructionLibraryFilter
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.settings.InstructionsViewModel
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Instructions settings category.
 *
 * The route keeps the ViewModel wiring and breadcrumb updates together so the visible pages stay
 * separate from the underlying library selection. Selection state is owned by the
 * [InstructionsViewModel]; this route only observes it to decide between the list and detail pages.
 *
 * @param authState Authentication context (currently unused by the Instructions tab; the library and
 *   the role catalog it reads are loaded from token-scoped endpoints).
 * @param viewModel Instructions ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param categoryResetSignal Incremented when the user re-selects this category in the sidebar;
 *   triggers a reset to the list view.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the current Instructions
 *   page in the breadcrumb trail.
 */
@Composable
fun InstructionsTabRoute(
    authState: AuthState.Authenticated,
    modifier: Modifier = Modifier,
    viewModel: InstructionsViewModel = koinViewModel(),
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    // Tab-local initial load of the library plus the role and project catalogs the usage labels, the
    // filters and the assign picker read.
    LaunchedEffect(Unit) {
        viewModel.loadInstructionsAndRoles()
    }

    // Reset to list view when the category is re-selected in the sidebar.
    LaunchedEffect(categoryResetSignal) {
        if (categoryResetSignal > 0) {
            viewModel.selectInstruction(null)
        }
    }

    val instructionsState by viewModel.instructionsUiState.collectAsState()
    val selectedInstruction by viewModel.selectedInstruction.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val projectFilter by viewModel.projectFilter.collectAsState()
    val rolesState by viewModel.rolesState.collectAsState()
    val rolesById by viewModel.rolesById.collectAsState()
    val projectsState by viewModel.projectsState.collectAsState()
    val projectsById by viewModel.projectsById.collectAsState()

    val breadcrumbs = selectedInstruction?.let {
        listOf("Settings", SettingsCategory.Instructions.displayLabel, it.name)
    } ?: listOf("Settings", SettingsCategory.Instructions.displayLabel)

    LaunchedEffect(breadcrumbs) {
        onBreadcrumbsChanged(breadcrumbs)
    }

    val state = InstructionsTabState(
        instructionsUiState = instructionsState,
        selectedInstruction = selectedInstruction,
        dialogState = dialogState,
        filter = filter,
        projectFilter = projectFilter,
        roles = rolesState.dataOrNull.orEmpty(),
        rolesById = rolesById,
        projects = projectsState.dataOrNull.orEmpty(),
        projectsById = projectsById
    )

    val actions = object : InstructionsTabActions {
        override fun onLoadInstructionsAndRoles() = viewModel.loadInstructionsAndRoles()
        override fun onSelectInstruction(instruction: AgentInstructionDto?) = viewModel.selectInstruction(instruction)
        override fun onRoleFilterChanged(filter: InstructionLibraryFilter) = viewModel.setFilter(filter)
        override fun onProjectFilterChanged(filter: AgentRoleFilter) = viewModel.setProjectFilter(filter)
        override fun onStartDeletingInstruction(instruction: AgentInstructionDto) =
            viewModel.startDeletingInstruction(instruction)

        override fun onDeleteInstruction(instructionId: Long) = viewModel.deleteInstruction(instructionId)
        override fun onStartEditingInstruction(instruction: AgentInstructionDto) =
            viewModel.startEditingInstruction(instruction)

        override fun onEditInstructionNameChanged(name: String) = viewModel.editInstructionName(name)
        override fun onEditInstructionMessageChanged(message: String) = viewModel.editInstructionMessage(message)
        override fun onSaveInstructionEdit() = viewModel.saveInstructionEdit()
        override fun onAssignToRole(roleId: Long) = viewModel.assignToRole(roleId)
        override fun onUnassignFromRole(roleId: Long) = viewModel.unassignFromRole(roleId)
        override fun onCancelDialog() = viewModel.cancelDialog()
    }

    InstructionsTab(
        state = state,
        actions = actions,
        onOpenInstructionDetails = { instruction -> viewModel.selectInstruction(instruction) },
        onBackToInstructionList = { viewModel.selectInstruction(null) },
        modifier = modifier
    )
}
