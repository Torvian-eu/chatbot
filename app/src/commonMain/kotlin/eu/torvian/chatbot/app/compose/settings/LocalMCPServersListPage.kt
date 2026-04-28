package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerOverview
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerOperation

/**
 * Full-width list page for the MCP Servers settings category.
 *
 * The page now owns the shared shell, header copy, and add action while the list
 * panel focuses on row rendering and empty-state behavior.
 *
 * @param serverOverviews All known MCP server entries, including runtime status.
 * @param selectedServerId Identifier of the currently opened server, if any.
 * @param onServerSelected Callback invoked when the user opens a server detail page.
 * @param onAddNewServer Callback invoked when the user starts the add-server flow.
 * @param operationInProgress Currently running server operation, used for list badges.
 * @param modifier Modifier applied to the full-width page container.
 */
@Composable
fun LocalMCPServersListPage(
    serverOverviews: List<LocalMCPServerOverview>,
    selectedServerId: Long?,
    onServerSelected: (Long) -> Unit,
    onAddNewServer: () -> Unit,
    operationInProgress: LocalMCPServerOperation?,
    modifier: Modifier = Modifier
) {
    SettingsListPageTemplate(
        title = "MCP Servers",
        subtitle = if (serverOverviews.isEmpty()) {
            "No MCP servers configured yet. Use the add action to create your first server."
        } else {
            "${serverOverviews.size} configured • select a server to inspect tools and runtime state."
        },
        modifier = modifier,
        actions = {
            FilledTonalButton(onClick = onAddNewServer) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add server", maxLines = 1, softWrap = false)
            }
        }
    ) {
        LocalMCPServersListPanel(
            serverOverviews = serverOverviews,
            selectedServerId = selectedServerId,
            onServerSelected = onServerSelected,
            operationInProgress = operationInProgress,
            modifier = Modifier.fillMaxSize()
        )
    }
}
