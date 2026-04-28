package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerOverview
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerOperation

/**
 * Body content for the MCP servers list page.
 *
 * The shared list-page shell now owns the page title and add action, so this
 * composable focuses on the server rows and empty-state behavior only.
 */
@Composable
fun LocalMCPServersListPanel(
    serverOverviews: List<LocalMCPServerOverview>,
    selectedServerId: Long?,
    onServerSelected: (Long) -> Unit,
    operationInProgress: LocalMCPServerOperation?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (serverOverviews.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No MCP servers configured yet.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Use the add action in the header to create your first server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                serverOverviews.forEach { overview ->
                    val server: LocalMCPServerDto = overview.serverConfig
                    val isOperating = when (operationInProgress) {
                        is LocalMCPServerOperation.TestingConnection -> operationInProgress.serverId == server.id
                        is LocalMCPServerOperation.RefreshingTools -> operationInProgress.serverId == server.id
                        is LocalMCPServerOperation.StartingServer -> operationInProgress.serverId == server.id
                        is LocalMCPServerOperation.StoppingServer -> operationInProgress.serverId == server.id
                        null -> false
                    }

                    LocalMCPServerListItem(
                        server = server,
                        overview = overview,
                        isSelected = server.id == selectedServerId,
                        isOperating = isOperating,
                        onClick = { onServerSelected(server.id) }
                    )
                }
            }
        }
    }
}

/**
 * Individual list item for an MCP server.
 */
@Composable
fun LocalMCPServerListItem(
    server: LocalMCPServerDto,
    overview: LocalMCPServerOverview?,
    isSelected: Boolean,
    isOperating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        tonalElevation = if (isSelected) 3.dp else 0.dp,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Badge(
                    containerColor = if (server.isEnabled) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = if (server.isEnabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (overview != null) {
                    Badge(
                        containerColor = when {
                            isOperating -> MaterialTheme.colorScheme.primaryContainer
                            overview.isConnected -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = when {
                                isOperating -> "Operating..."
                                overview.isConnected -> "Connected"
                                else -> "Stopped"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    val toolCount = overview.tools?.size ?: 0
                    Text(
                        text = if (toolCount > 0) "$toolCount tools" else "No tools",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
