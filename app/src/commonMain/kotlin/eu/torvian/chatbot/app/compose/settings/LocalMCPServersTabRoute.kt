package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the MCP Servers tab that manages its own ViewModel and state.
 * This follows the Route pattern for better modularity and testability.
 */
@Composable
fun LocalMCPServersTabRoute(
    authState: AuthState.Authenticated,
    viewModel: LocalMCPServerViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    // Tab-local initial load
    LaunchedEffect(Unit) {
        viewModel.loadServers(authState.userId)
    }

    // Collect tab state
    val serverOverviews by viewModel.serverOverviews.collectAsState()
    val selectedServerOverview by viewModel.selectedServerOverview.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()
    val operationInProgress by viewModel.operationInProgress.collectAsState()

    // Build presentational state
    val state = LocalMCPServersTabState(
        serverOverviews = serverOverviews,
        selectedServerOverview = selectedServerOverview,
        dialogState = dialogState,
        operationInProgress = operationInProgress
    )

    // Build actions forwarding to VM
    val actions = object : LocalMCPServersTabActions {
        override fun onLoadServers(userId: Long) = viewModel.loadServers(userId)
        override fun onSelectServer(serverId: Long?) = viewModel.selectServer(serverId)
        override fun onStartAddingNewServer() = viewModel.startAddingNewServer()
        override fun onStartEditingServer(server: eu.torvian.chatbot.app.domain.models.LocalMCPServer) =
            viewModel.startEditingServer(server)
        override fun onStartDeletingServer(server: eu.torvian.chatbot.app.domain.models.LocalMCPServer) =
            viewModel.startDeletingServer(server)
        override fun onCancelDialog() = viewModel.cancelDialog()
        override fun onUpdateServerForm(update: (eu.torvian.chatbot.app.viewmodel.LocalMCPServerFormState) -> eu.torvian.chatbot.app.viewmodel.LocalMCPServerFormState) =
            viewModel.updateServerForm(update)
        override fun onSaveServer() = viewModel.saveServer()
        override fun onDeleteServer(serverId: Long) = viewModel.deleteServer(serverId)
        override fun onTestConnection(serverId: Long) = viewModel.testConnection(serverId)
        override fun onRefreshTools(serverId: Long) = viewModel.refreshTools(serverId)
        override fun onStartServer(serverId: Long) = viewModel.startServer(serverId)
        override fun onStopServer(serverId: Long) = viewModel.stopServer(serverId)
        override fun onToggleServerEnabled(server: eu.torvian.chatbot.app.domain.models.LocalMCPServer) =
            viewModel.toggleServerEnabled(server)
    }

    // Call the presentational LocalMCPServersTab
    LocalMCPServersTab(
        state = state,
        actions = actions,
        authState = authState,
        modifier = modifier
    )
}

