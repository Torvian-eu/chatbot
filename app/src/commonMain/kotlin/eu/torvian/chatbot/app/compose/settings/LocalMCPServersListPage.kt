package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerOverview
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerOperation

/**
 * Full-width list page for the MCP Servers settings category.
 *
 * The page keeps the list-specific chrome in the existing list panel while the
 * route controls whether the category is currently showing the list or a server
 * detail page.
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
    LocalMCPServersListPanel(
        serverOverviews = serverOverviews,
        selectedServerId = selectedServerId,
        onServerSelected = onServerSelected,
        onAddNewServer = onAddNewServer,
        operationInProgress = operationInProgress,
        modifier = modifier
    )
}
