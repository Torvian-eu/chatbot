package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
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
    mcpServers: List<LocalMCPServerDto>,
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
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Non-MCP tools section
                        if (nonMcpTools.isNotEmpty()) {
                            ToolSection(
                                title = "Built-in Tools",
                                tools = nonMcpTools,
                                enabledToolIds = enabledToolIds,
                                onToggleTool = onToggleTool,
                                onToggleTools = onToggleTools
                            )
                        }

                        // MCP tools sections (one per server)
                        toolsByServer.forEach { (serverId, tools) ->
                            val server = mcpServersById[serverId]
                            ToolSection(
                                title = server?.name ?: "Unknown Server ($serverId)",
                                tools = tools,
                                enabledToolIds = enabledToolIds,
                                onToggleTool = onToggleTool,
                                onToggleTools = onToggleTools,
                                isCollapsedByDefault = true
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
    onToggleTools: (List<ToolDefinition>, Boolean) -> Unit,
    isCollapsedByDefault: Boolean = false
) {
    var expanded by remember { mutableStateOf(!isCollapsedByDefault) }
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
    val truncatedDescription = if (tool.description.length > 100) {
        tool.description.take(100) + "..."
    } else {
        tool.description
    }

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
            PlainTooltipBox(
                text = tool.description,
                showDelay = 500
            ) {
                Text(
                    text = truncatedDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle
        )
    }
}

