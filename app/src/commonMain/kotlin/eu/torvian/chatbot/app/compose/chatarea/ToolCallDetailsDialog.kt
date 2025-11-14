package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import kotlinx.serialization.json.Json

/**
 * Dialog displaying detailed information about a tool call.
 */
@Composable
fun ToolCallDetailsDialog(
    toolCall: ToolCall,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Tool: ${toolCall.toolName}")
                toolCall.durationMs?.let { duration ->
                    Text(
                        text = "Executed in ${duration}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status section
                ToolCallStatusSection(toolCall)

                HorizontalDivider()

                // Input section
                ToolCallDataSection(
                    title = "Input",
                    data = toolCall.input,
                    emptyText = "No input data"
                )

                HorizontalDivider()

                // Output section
                ToolCallDataSection(
                    title = "Output",
                    data = toolCall.output,
                    emptyText = if (toolCall.status == ToolCallStatus.PENDING)
                        "Execution in progress..."
                    else
                        "No output data"
                )

                // Error section (if applicable)
                toolCall.errorMessage?.let { error ->
                    HorizontalDivider()
                    ToolCallErrorSection(error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        modifier = Modifier.widthIn(min = 500.dp, max = 700.dp)
    )
}

@Composable
private fun ToolCallStatusSection(toolCall: ToolCall) {
    val (statusText, statusColor) = when (toolCall.status) {
        ToolCallStatus.PENDING -> "Pending" to MaterialTheme.colorScheme.tertiary
        ToolCallStatus.SUCCESS -> "Success" to MaterialTheme.colorScheme.primary
        ToolCallStatus.ERROR -> "Error" to MaterialTheme.colorScheme.error
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Status:", style = MaterialTheme.typography.labelMedium)
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = statusColor
        )
    }
}

@Composable
private fun ToolCallDataSection(
    title: String,
    data: String?,
    emptyText: String
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(4.dp))

        if (data.isNullOrBlank()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Try to pretty-print JSON, fall back to raw string
            val formattedData = try {
                val json = Json { prettyPrint = true }
                json.parseToJsonElement(data).toString()
            } catch (_: Exception) {
                data
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = formattedData,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun ToolCallErrorSection(errorMessage: String) {
    Column {
        Text(
            text = "Error",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(4.dp))

        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

