package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerOverview
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerOperation
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference

/**
 * Full-width details page for a single MCP server.
 *
 * The page owns the back navigation chrome and delegates the actual server
 * management actions to the existing detail panel so dialog and repository flows
 * stay unchanged.
 *
 * @param serverOverview The selected server overview, or null while data is still
 * synchronising after a selection change.
 * @param toolApprovalPreferences Tool approval preferences for the selected server.
 * @param onBackToList Callback invoked when the user returns to the list page.
 * @param onEditServer Callback used to open the edit-server dialog.
 * @param onDeleteServer Callback used to open the delete confirmation dialog.
 * @param onTestConnection Callback used to trigger a connection test.
 * @param onRefreshTools Callback used to refresh discovered tools.
 * @param onStartServer Callback used to start the server process.
 * @param onStopServer Callback used to stop the server process.
 * @param onToggleEnabled Callback used to enable or disable the server.
 * @param onToggleToolEnabled Callback used to toggle a tool's enabled state.
 * @param onEditTool Callback used to open the tool edit dialog.
 * @param onEnableAllTools Callback used to enable every discovered tool on the server.
 * @param onDisableAllTools Callback used to disable every discovered tool on the server.
 * @param onDeleteToolApprovalPreference Callback used to remove a tool approval preference.
 * @param operationInProgress Currently running server operation, used for status-aware actions.
 * @param modifier Modifier applied to the full-width page container.
 */
@Composable
fun LocalMCPServerDetailsPage(
    serverOverview: LocalMCPServerOverview?,
    toolApprovalPreferences: DataState<RepositoryError, List<UserToolApprovalPreference>>,
    onBackToList: () -> Unit,
    onEditServer: (LocalMCPServerOverview) -> Unit,
    onDeleteServer: (LocalMCPServerOverview) -> Unit,
    onTestConnection: (LocalMCPServerOverview) -> Unit,
    onRefreshTools: (LocalMCPServerOverview) -> Unit,
    onStartServer: (LocalMCPServerOverview) -> Unit,
    onStopServer: (LocalMCPServerOverview) -> Unit,
    onToggleEnabled: (LocalMCPServerOverview) -> Unit,
    onToggleToolEnabled: (LocalMCPToolDefinition) -> Unit,
    onEditTool: (LocalMCPToolDefinition) -> Unit,
    onEnableAllTools: (Long) -> Unit,
    onDisableAllTools: (Long) -> Unit,
    onDeleteToolApprovalPreference: (Long) -> Unit,
    operationInProgress: LocalMCPServerOperation?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onBackToList) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to MCP servers list"
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MCP Servers",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = serverOverview?.serverConfig?.name ?: "Loading MCP server details...",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }

            HorizontalDivider()

            Spacer(modifier = Modifier.height(12.dp))

            if (serverOverview == null) {
                // Keep the page shell visible while the selection is synchronising or a reload is in flight.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Loading MCP server details...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LocalMCPServerDetailPanel(
                    serverOverview = serverOverview,
                    toolApprovalPreferences = toolApprovalPreferences,
                    onEditServer = onEditServer,
                    onDeleteServer = onDeleteServer,
                    onTestConnection = onTestConnection,
                    onRefreshTools = onRefreshTools,
                    onStartServer = onStartServer,
                    onStopServer = onStopServer,
                    onToggleEnabled = onToggleEnabled,
                    onToggleToolEnabled = onToggleToolEnabled,
                    onEditTool = onEditTool,
                    onEnableAllTools = onEnableAllTools,
                    onDisableAllTools = onDisableAllTools,
                    onDeleteToolApprovalPreference = onDeleteToolApprovalPreference,
                    operationInProgress = operationInProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}


