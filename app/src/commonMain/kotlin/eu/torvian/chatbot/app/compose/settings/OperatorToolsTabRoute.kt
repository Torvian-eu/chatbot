package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.settings.OperatorToolsViewModel
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Operator Tools settings category.
 *
 * The route owns the ViewModel wiring, the initial tool-list load, and the breadcrumb updates.
 * It observes the ViewModel's reactive state and forwards user actions down to the presentational
 * [OperatorToolsTab].
 *
 * @param authState Authentication context for the active session.
 * @param viewModel Operator Tools ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param categoryResetSignal Incremented when the user re-selects this category in the
 *   sidebar; reserved for future list/detail navigation state.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the current
 *   Operator Tools page in the breadcrumb trail.
 */
@Composable
fun OperatorToolsTabRoute(
    authState: AuthState.Authenticated,
    modifier: Modifier = Modifier,
    viewModel: OperatorToolsViewModel = koinViewModel(),
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    // Ensure the tool list and approval preferences have data on first entry.
    LaunchedEffect(Unit) {
        viewModel.loadToolsIfNeeded()
    }

    // Collect tab state.
    val toolsState by viewModel.toolsState.collectAsState()
    val approvalPreferencesState by viewModel.approvalPreferencesState.collectAsState()
    val resetInProgress by viewModel.resetInProgress.collectAsState()

    // Update breadcrumbs.
    LaunchedEffect(Unit) {
        onBreadcrumbsChanged(listOf("Settings", SettingsCategory.OperatorTools.displayLabel))
    }

    // Build presentational state.
    val state = OperatorToolsTabState(
        toolsState = toolsState,
        approvalPreferencesState = approvalPreferencesState,
        resetInProgress = resetInProgress
    )

    // Build actions forwarding to the ViewModel.
    val actions = object : OperatorToolsTabActions {
        override fun onLoadTools() = viewModel.loadToolsIfNeeded()
        override fun onToggleToolEnabled(tool: OperatorToolDefinition) =
            viewModel.toggleToolEnabled(tool)
        override fun onUpdateTool(tool: OperatorToolDefinition) = viewModel.updateTool(tool)
        override fun onSetApprovalPreference(toolDefinitionId: Long, autoApprove: Boolean) =
            viewModel.setApprovalPreference(toolDefinitionId, autoApprove)
        override fun onClearApprovalPreference(toolDefinitionId: Long) =
            viewModel.clearApprovalPreference(toolDefinitionId)
        override fun onResetToDefaults() = viewModel.resetToDefaults()
    }

    // Call the presentational OperatorToolsTab.
    OperatorToolsTab(
        state = state,
        actions = actions,
        modifier = modifier
    )
}
