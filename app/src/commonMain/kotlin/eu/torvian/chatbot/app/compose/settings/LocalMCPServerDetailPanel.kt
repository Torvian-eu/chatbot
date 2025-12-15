package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerOverview
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerOperation
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Detail panel showing information and actions for the selected MCP server.
 */
@Composable
fun LocalMCPServerDetailPanel(
    serverOverview: LocalMCPServerOverview?,
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
    operationInProgress: LocalMCPServerOperation?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        if (serverOverview == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Select an MCP server to view details",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val server = serverOverview.serverConfig
            val isOperating = when (operationInProgress) {
                is LocalMCPServerOperation.TestingConnection -> operationInProgress.serverId == server.id
                is LocalMCPServerOperation.RefreshingTools -> operationInProgress.serverId == server.id
                is LocalMCPServerOperation.StartingServer -> operationInProgress.serverId == server.id
                is LocalMCPServerOperation.StoppingServer -> operationInProgress.serverId == server.id
                null -> false
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header with server name and actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { onEditServer(serverOverview) }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Server"
                            )
                        }
                        IconButton(onClick = { onDeleteServer(serverOverview) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Server",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Description
                if (!server.description.isNullOrBlank()) {
                    Text(
                        text = server.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Status Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Status",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        DetailRow(
                            label = "Enabled",
                            value = if (server.isEnabled) "Yes" else "No"
                        )

                        DetailRow(
                            label = "Connection",
                            value = when {
                                isOperating -> "Operating..."
                                serverOverview.isConnected -> "Connected"
                                else -> "Disconnected"
                            }
                        )

                        serverOverview.processStatus?.let { status ->
                            DetailRow(
                                label = "Process",
                                value = status.toString()
                            )
                        }

                        serverOverview.tools?.let { tools ->
                            DetailRow(
                                label = "Tools",
                                value = "${tools.size} discovered"
                            )
                        }

                        serverOverview.connectedAt?.let { timestamp ->
                            val localDateTime = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
                            DetailRow(
                                label = "Connected At",
                                value = localDateTime.toString()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Text(
                    text = "Actions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Toggle Enabled/Disabled
                    Button(
                        onClick = { onToggleEnabled(serverOverview) },
                        enabled = !isOperating
                    ) {
                        Icon(
                            imageVector = if (server.isEnabled) Icons.Default.Close else Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (server.isEnabled) "Disable" else "Enable")
                    }

                    // Test Connection
                    OutlinedButton(
                        onClick = { onTestConnection(serverOverview) },
                        enabled = !isOperating && server.isEnabled
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Connection")
                    }

                    // Refresh Tools
                    OutlinedButton(
                        onClick = { onRefreshTools(serverOverview) },
                        enabled = !isOperating && server.isEnabled
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Refresh Tools")
                    }

                    // Start/Stop Server
                    if (serverOverview.isConnected) {
                        OutlinedButton(
                            onClick = { onStopServer(serverOverview) },
                            enabled = !isOperating,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Stop Server")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onStartServer(serverOverview) },
                            enabled = !isOperating && server.isEnabled
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Server")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Configuration Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Configuration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        DetailRow(label = "Command", value = server.command)

                        if (server.arguments.isNotEmpty()) {
                            DetailRow(
                                label = "Arguments",
                                value = server.arguments.joinToString(" ")
                            )
                        }

                        if (server.environmentVariables.isNotEmpty()) {
                            DetailRow(
                                label = "Env Vars",
                                value = "${server.environmentVariables.size} configured"
                            )
                        }

                        server.workingDirectory?.let { dir ->
                            DetailRow(label = "Working Dir", value = dir)
                        }

                        DetailRow(
                            label = "Auto-start on Enable",
                            value = if (server.autoStartOnEnable) "Yes" else "No"
                        )

                        DetailRow(
                            label = "Auto-start on Launch",
                            value = if (server.autoStartOnLaunch) "Yes" else "No"
                        )

                        DetailRow(
                            label = "Auto-stop Timeout",
                            value = when (server.autoStopAfterInactivitySeconds) {
                                null -> "Default (5 min)"
                                0 -> "Never"
                                else -> "${server.autoStopAfterInactivitySeconds}s"
                            }
                        )
                    }
                }

                // Tools Section
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val toolsList = serverOverview.tools
                        val toolCount = toolsList?.size ?: 0

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Tools ($toolCount)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            // Enable/Disable All links
                            if (!toolsList.isNullOrEmpty()) {
                                val allEnabled = toolsList.all { it.isEnabled }
                                val allDisabled = toolsList.none { it.isEnabled }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = { onEnableAllTools(server.id) },
                                        enabled = !allEnabled
                                    ) {
                                        Text(
                                            text = "Enable All",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }

                                    TextButton(
                                        onClick = { onDisableAllTools(server.id) },
                                        enabled = !allDisabled
                                    ) {
                                        Text(
                                            text = "Disable All",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (toolsList.isNullOrEmpty()) {
                            Text(
                                text = if (toolsList == null) {
                                    "No tools discovered yet. Click 'Refresh Tools' to discover available tools."
                                } else {
                                    "No tools available from this server."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            toolsList.forEach { tool ->
                                ToolListItem(
                                    tool = tool,
                                    onToggleEnabled = onToggleToolEnabled,
                                    onEdit = onEditTool
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolListItem(
    tool: LocalMCPToolDefinition,
    onToggleEnabled: (LocalMCPToolDefinition) -> Unit,
    onEdit: (LocalMCPToolDefinition) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource),
        color = if (hovered) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Tool icon and info
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (tool.isEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tool.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (tool.isEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (tool.description.isNotBlank()) {
                        Text(
                            text = tool.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Actions: Edit button (hover-visible) and Switch (always visible)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Edit button placeholder (reserves space)
                Box(modifier = Modifier.size(32.dp)) {
                    if (hovered) {
                        IconButton(
                            onClick = { onEdit(tool) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit tool ${tool.name}",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Enable/Disable switch (always visible)
                Switch(
                    checked = tool.isEnabled,
                    onCheckedChange = { onToggleEnabled(tool) },
                    modifier = Modifier.height(32.dp)
                )
            }
        }
    }
}
