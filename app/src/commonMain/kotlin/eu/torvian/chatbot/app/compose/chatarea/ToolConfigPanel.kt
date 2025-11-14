package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.tool.ToolDefinition

/**
 * Dialog for configuring which tools are enabled for the current session.
 */
@Composable
fun ToolConfigDialog(
    availableTools: List<ToolDefinition>,
    enabledTools: List<ToolDefinition>,
    onToggleTool: (ToolDefinition, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val enabledToolIds = enabledTools.map { it.id }.toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure Tools for This Session") },
        text = {
            if (availableTools.isEmpty()) {
                Text("No tools are currently available.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availableTools) { tool ->
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        modifier = Modifier.widthIn(min = 400.dp, max = 600.dp)
    )
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

