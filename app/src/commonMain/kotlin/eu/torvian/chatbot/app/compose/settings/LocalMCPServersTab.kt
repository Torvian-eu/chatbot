package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.AuthState

/**
 * MCP Servers management tab with master-detail layout.
 * Implements US6.4 - Local MCP Server Management UI.
 */
@Composable
fun LocalMCPServersTab(
    state: LocalMCPServersTabState,
    actions: LocalMCPServersTabActions,
    authState: AuthState.Authenticated,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (val overviewsState = state.serverOverviews) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading MCP servers...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load MCP servers",
                    error = overviewsState.error,
                    onRetry = { actions.onLoadServers(authState.userId) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val serverOverviews = overviewsState.data

                Row(modifier = Modifier.fillMaxSize()) {
                    // Master: Servers List
                    LocalMCPServersListPanel(
                        serverOverviews = serverOverviews,
                        selectedServerId = state.selectedServerOverview?.serverId,
                        onServerSelected = { serverId -> actions.onSelectServer(serverId) },
                        onAddNewServer = { actions.onStartAddingNewServer() },
                        operationInProgress = state.operationInProgress,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    // Detail: Server Details/Actions
                    LocalMCPServerDetailPanel(
                        serverOverview = state.selectedServerOverview,
                        onEditServer = { actions.onStartEditingServer(it.serverConfig) },
                        onDeleteServer = { actions.onStartDeletingServer(it.serverConfig) },
                        onTestConnection = { actions.onTestConnection(it.serverId) },
                        onRefreshTools = { actions.onRefreshTools(it.serverId) },
                        onStartServer = { actions.onStartServer(it.serverId) },
                        onStopServer = { actions.onStopServer(it.serverId) },
                        onToggleEnabled = { actions.onToggleServerEnabled(it.serverConfig) },
                        onToggleToolEnabled = { actions.onToggleToolEnabled(it) },
                        onEditTool = { actions.onStartEditingTool(it) },
                        onEnableAllTools = { actions.onEnableAllTools(it) },
                        onDisableAllTools = { actions.onDisableAllTools(it) },
                        operationInProgress = state.operationInProgress,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 16.dp)
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
                            text = "Click to load MCP servers",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { actions.onLoadServers(authState.userId) }) {
                            Text("Load MCP Servers")
                        }
                    }
                }
            }
        }

        // Dialog handling based on dialog state
        LocalMCPServerDialogs(
            dialogState = state.dialogState,
            onUpdateForm = actions::onUpdateServerForm,
            onSaveServer = actions::onSaveServer,
            onDeleteServer = actions::onDeleteServer,
            onUpdateToolForm = actions::onUpdateToolForm,
            onSaveTool = actions::onSaveTool,
            onDismiss = actions::onCancelDialog
        )
    }
}
