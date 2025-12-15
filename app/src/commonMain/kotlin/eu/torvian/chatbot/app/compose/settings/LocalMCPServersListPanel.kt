package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerOverview
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerOperation

/**
 * List panel showing all configured MCP servers.
 */
@Composable
fun LocalMCPServersListPanel(
    serverOverviews: List<LocalMCPServerOverview>,
    selectedServerId: Long?,
    onServerSelected: (Long) -> Unit,
    onAddNewServer: () -> Unit,
    operationInProgress: LocalMCPServerOperation?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with Add button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MCP Servers",
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onAddNewServer) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add MCP Server"
                    )
                }
            }

            HorizontalDivider()

            // Server list
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
                            text = "No MCP servers configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onAddNewServer) {
                            Text("Add Your First Server")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(serverOverviews, key = { it.serverConfig.id }) { overview ->
                        val server: LocalMCPServer = overview.serverConfig
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
}

/**
 * Individual list item for an MCP server.
 */
@Composable
fun LocalMCPServerListItem(
    server: LocalMCPServer,
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
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Status indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Enabled/Disabled badge
                    Badge(
                        containerColor = if (server.isEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = if (server.isEnabled) "Enabled" else "Disabled",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    // Connection status
                    if (overview != null) {
                        Badge(
                            containerColor = when {
                                isOperating -> MaterialTheme.colorScheme.tertiary
                                overview.isConnected -> MaterialTheme.colorScheme.primary
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

                        // Tool count
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
}
