package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerFormState
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerViewModel
import eu.torvian.chatbot.app.viewmodel.LocalMCPToolFormState
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the MCP Servers settings category.
 *
 * The route keeps the ViewModel wiring, the category-local page id, and the
 * breadcrumb updates together so page switching stays separate from dialog state.
 *
 * @param authState Authentication context for permission-sensitive server actions.
 * @param viewModel MCP server ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the
 * current MCP Servers page in the breadcrumb trail.
 */
@Composable
fun LocalMCPServersTabRoute(
    authState: AuthState.Authenticated,
    viewModel: LocalMCPServerViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    var selectedServerId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Tab-local initial load
    LaunchedEffect(Unit) {
        viewModel.loadServers(authState.userId)
    }

    // Collect tab state
    val serverOverviews by viewModel.serverOverviews.collectAsState()
    val selectedServerOverview by viewModel.selectedServerOverview.collectAsState()
    val selectedServerToolApprovalPreferences by viewModel.selectedServerToolApprovalPreferences.collectAsState()
    val workers by viewModel.workers.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()
    val operationInProgress by viewModel.operationInProgress.collectAsState()

    // Keep the route-local page id aligned with ViewModel selection changes.
    LaunchedEffect(serverOverviews, selectedServerOverview?.serverId) {
        val selectedOverviewId = selectedServerOverview?.serverId
        if (selectedOverviewId != null) {
            if (selectedServerId != selectedOverviewId) {
                selectedServerId = selectedOverviewId
            }
        } else if (serverOverviews is DataState.Success && selectedServerId != null) {
            selectedServerId = null
        }
    }

    val activeServerOverview = selectedServerOverview
    val breadcrumbs = if (activeServerOverview != null && selectedServerId != null) {
        listOf(
            "Settings",
            SettingsCategory.McpServers.displayLabel,
            activeServerOverview.serverConfig.name
        )
    } else {
        listOf("Settings", SettingsCategory.McpServers.displayLabel)
    }

    LaunchedEffect(breadcrumbs) {
        onBreadcrumbsChanged(breadcrumbs)
    }

    // Build presentational state
    val state = LocalMCPServersTabState(
        serverOverviews = serverOverviews,
        selectedServerOverview = selectedServerOverview,
        selectedServerToolApprovalPreferences = selectedServerToolApprovalPreferences,
        workers = workers,
        dialogState = dialogState,
        operationInProgress = operationInProgress
    )

    // Build actions forwarding to VM
    val actions = object : LocalMCPServersTabActions {
        override fun onLoadServers(userId: Long) = viewModel.loadServers(userId)
        override fun onSelectServer(serverId: Long?) = viewModel.selectServer(serverId)
        override fun onStartAddingNewServer() = viewModel.startAddingNewServer()
        override fun onStartEditingServer(server: LocalMCPServerDto) =
            viewModel.startEditingServer(server)
        override fun onStartDeletingServer(server: LocalMCPServerDto) =
            viewModel.startDeletingServer(server)
        override fun onCancelDialog() = viewModel.cancelDialog()
        override fun onUpdateServerForm(update: (LocalMCPServerFormState) -> LocalMCPServerFormState) =
            viewModel.updateServerForm(update)
        override fun onSaveServer() = viewModel.saveServer()
        override fun onDeleteServer(serverId: Long) = viewModel.deleteServer(serverId)
        override fun onTestConnection(serverId: Long) = viewModel.testConnection(serverId)
        override fun onTestServerInDialog() = viewModel.testServerInDialog()
        override fun onRefreshTools(serverId: Long) = viewModel.refreshTools(serverId)
        override fun onStartServer(serverId: Long) = viewModel.startServer(serverId)
        override fun onStopServer(serverId: Long) = viewModel.stopServer(serverId)
        override fun onToggleServerEnabled(server: LocalMCPServerDto) =
            viewModel.toggleServerEnabled(server)
        override fun onToggleToolEnabled(tool: LocalMCPToolDefinition) =
            viewModel.toggleToolEnabled(tool)
        override fun onStartEditingTool(tool: LocalMCPToolDefinition) =
            viewModel.startEditingTool(tool)
        override fun onEnableAllTools(serverId: Long) = viewModel.enableAllTools(serverId)
        override fun onDisableAllTools(serverId: Long) = viewModel.disableAllTools(serverId)
        override fun onUpdateToolForm(update: (LocalMCPToolFormState) -> LocalMCPToolFormState) =
            viewModel.updateToolForm(update)
        override fun onSaveTool() = viewModel.saveTool()
        override fun onDeleteToolApprovalPreference(toolDefinitionId: Long) =
            viewModel.deleteToolApprovalPreference(toolDefinitionId)
        override fun onReloadWorkers() = viewModel.reloadWorkers()
    }

    // Call the presentational LocalMCPServersTab
    LocalMCPServersTab(
        state = state,
        actions = actions,
        authState = authState,
        selectedServerId = selectedServerId,
        onOpenServerDetails = { serverId ->
            selectedServerId = serverId
            actions.onSelectServer(serverId)
        },
        onBackToServerList = {
            selectedServerId = null
            actions.onSelectServer(null)
        },
        modifier = modifier
    )
}

