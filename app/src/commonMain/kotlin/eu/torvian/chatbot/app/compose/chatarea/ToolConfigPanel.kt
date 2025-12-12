package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolDefinition

/**
 * Dialog for configuring which tools are enabled for the current session.
 * Groups MCP tools by server and shows non-MCP tools separately.
 */
@Composable
fun ToolConfigDialog(
    availableTools: List<ToolDefinition>,
    enabledTools: List<ToolDefinition>,
    mcpServers: List<LocalMCPServer>,
    onToggleTool: (ToolDefinition, Boolean) -> Unit,
    onToggleTools: (List<ToolDefinition>, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val enabledToolIds = enabledTools.map { it.id }.toSet()
    val mcpServersById = mcpServers.associateBy { it.id }

    // Separate tools by type
    val mcpTools = availableTools.filterIsInstance<LocalMCPToolDefinition>()
    val nonMcpTools = availableTools.filterNot { it is LocalMCPToolDefinition }

    // Group MCP tools by server and filter out tools from globally disabled servers
    val toolsByServer = mcpTools
        .filter { tool -> mcpServersById[tool.serverId]?.isEnabled == true }
        .groupBy { it.serverId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure Tools for This Session") },
        text = {
            if (availableTools.isEmpty()) {
                Text("No tools are currently available.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Non-MCP tools section
                    if (nonMcpTools.isNotEmpty()) {
                        item {
                            ToolSection(
                                title = "Built-in Tools",
                                tools = nonMcpTools,
                                enabledToolIds = enabledToolIds,
                                onToggleTool = onToggleTool,
                                onToggleTools = onToggleTools
                            )
                        }
                    }

                    // MCP tools sections (one per server)
                    toolsByServer.forEach { (serverId, tools) ->
                        item {
                            val server = mcpServersById[serverId]
                            ToolSection(
                                title = server?.name ?: "Unknown Server ($serverId)",
                                tools = tools,
                                enabledToolIds = enabledToolIds,
                                onToggleTool = onToggleTool,
                                onToggleTools = onToggleTools
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        modifier = Modifier.widthIn(min = 500.dp, max = 700.dp)
    )
}

/**
 * A collapsible section for a group of tools.
 */
@Composable
private fun ToolSection(
    title: String,
    tools: List<ToolDefinition>,
    enabledToolIds: Set<Long>,
    onToggleTool: (ToolDefinition, Boolean) -> Unit,
    onToggleTools: (List<ToolDefinition>, Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val allEnabled = tools.all { it.id in enabledToolIds }
    val someEnabled = tools.any { it.id in enabledToolIds }

    Column {
        // Section header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Enable/Disable All buttons
                TextButton(
                    onClick = { onToggleTools(tools, true) },
                    enabled = !allEnabled
                ) {
                    Text("Enable All", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    onClick = { onToggleTools(tools, false) },
                    enabled = someEnabled
                ) {
                    Text("Disable All", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }
        }

        // Tools list
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tools.forEach { tool ->
                    ToolConfigItem(
                        tool = tool,
                        isEnabled = tool.id in enabledToolIds,
                        onToggle = { enabled ->
                            onToggleTool(tool, enabled)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Individual tool configuration item with toggle switch.
 */
@Composable
private fun ToolConfigItem(
    tool: ToolDefinition,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = tool.name,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle
        )
    }
}

