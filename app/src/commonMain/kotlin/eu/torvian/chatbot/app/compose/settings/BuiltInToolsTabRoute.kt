package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.settings.BuiltInToolsViewModel
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Built-in Tools settings category.
 *
 * The route owns the ViewModel wiring, the initial worker-list load, and the breadcrumb
 * updates. It observes the ViewModel's reactive state and forwards user actions down to the
 * presentational [BuiltInToolsTab].
 *
 * @param authState Authentication context for the active session.
 * @param viewModel Built-in Tools ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param categoryResetSignal Incremented when the user re-selects this category in the
 *   sidebar; triggers a reset to the list view.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the current
 *   Built-in Tools page in the breadcrumb trail.
 */
@Composable
fun BuiltInToolsTabRoute(
    authState: AuthState.Authenticated,
    viewModel: BuiltInToolsViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    // Ensure the worker dropdown has data on first entry.
    LaunchedEffect(Unit) {
        viewModel.loadWorkersIfNeeded()
    }

    // Collect tab state.
    val workersState by viewModel.workersState.collectAsState()
    val selectedWorkerId by viewModel.selectedWorkerId.collectAsState()
    val toolsState by viewModel.toolsState.collectAsState()
    val approvalPreferencesState by viewModel.approvalPreferencesState.collectAsState()
    val resetInProgress by viewModel.resetInProgress.collectAsState()

    // Update breadcrumbs.
    LaunchedEffect(Unit) {
        onBreadcrumbsChanged(listOf("Settings", SettingsCategory.BuiltInTools.displayLabel))
    }

    // Build presentational state.
    val state = BuiltInToolsTabState(
        workersState = workersState,
        selectedWorkerId = selectedWorkerId,
        toolsState = toolsState,
        approvalPreferencesState = approvalPreferencesState,
        resetInProgress = resetInProgress
    )

    // Build actions forwarding to the ViewModel.
    val actions = object : BuiltInToolsTabActions {
        override fun onSelectWorker(workerId: Long?) = viewModel.selectWorker(workerId)
        override fun onLoadTools(workerId: Long) = viewModel.loadTools(workerId)
        override fun onToggleToolEnabled(tool: BuiltInWorkerToolDefinition) =
            viewModel.toggleToolEnabled(tool)
        override fun onUpdateTool(tool: BuiltInWorkerToolDefinition) = viewModel.updateTool(tool)
        override fun onSetApprovalPreference(toolDefinitionId: Long, autoApprove: Boolean) =
            viewModel.setApprovalPreference(toolDefinitionId, autoApprove)
        override fun onClearApprovalPreference(toolDefinitionId: Long) =
            viewModel.clearApprovalPreference(toolDefinitionId)
        override fun onResetToDefaults() = viewModel.resetToDefaults()
    }

    // Call the presentational BuiltInToolsTab.
    BuiltInToolsTab(
        state = state,
        actions = actions,
        modifier = modifier
    )
}
