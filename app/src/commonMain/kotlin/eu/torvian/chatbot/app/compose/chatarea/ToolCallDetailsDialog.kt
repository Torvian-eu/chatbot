package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import kotlinx.serialization.json.Json

/**
 * Dialog displaying detailed information about a tool call.
 * If the tool call is awaiting approval, approval actions will be shown.
 *
 * @param onApprove Optional callback for approving the tool call (shown when status is AWAITING_APPROVAL)
 * @param onDeny Optional callback for denying the tool call with optional reason (shown when status is AWAITING_APPROVAL)
 */
@Composable
fun ToolCallDetailsDialog(
    toolCall: ToolCall,
    onDismiss: () -> Unit,
    onApprove: (() -> Unit)? = null,
    onDeny: ((String?) -> Unit)? = null
) {
    var showDenialReasonField by remember { mutableStateOf(false) }
    var denialReason by remember { mutableStateOf("") }
    val isAwaitingApproval = toolCall.status == ToolCallStatus.AWAITING_APPROVAL && onApprove != null && onDeny != null

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
                // Show approval warning if awaiting approval
                if (isAwaitingApproval) {
                    Text(
                        text = "The AI wants to execute this tool. Please review the details and approve or deny execution.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                }

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
                    emptyText = when (toolCall.status) {
                        ToolCallStatus.PENDING -> "Execution in progress..."
                        ToolCallStatus.AWAITING_APPROVAL -> "Awaiting user approval..."
                        ToolCallStatus.EXECUTING -> "Tool is executing..."
                        ToolCallStatus.USER_DENIED -> "Tool call was denied by user"
                        else -> "No output data"
                    }
                )

                // Error section (if applicable)
                toolCall.errorMessage?.let { error ->
                    HorizontalDivider()
                    ToolCallErrorSection(error)
                }

                // Denial reason section (if applicable)
                toolCall.denialReason?.let { reason ->
                    HorizontalDivider()
                    ToolCallDenialReasonSection(reason)
                }

                // Denial reason input field (if awaiting approval and user clicked Deny)
                if (isAwaitingApproval && showDenialReasonField) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Denial Reason (Optional)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedTextField(
                            value = denialReason,
                            onValueChange = { denialReason = it },
                            placeholder = { Text("Enter reason for denying this tool call...") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.error,
                                focusedLabelColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isAwaitingApproval && !showDenialReasonField) {
                // Show Approve and Deny buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onApprove.invoke()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Approve")
                    }
                    Button(
                        onClick = { showDenialReasonField = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Deny")
                    }
                }
            } else if (isAwaitingApproval && showDenialReasonField) {
                // Show Confirm Denial and Cancel buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showDenialReasonField = false }) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onDeny.invoke(denialReason.ifBlank { null })
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Confirm Denial")
                    }
                }
            } else {
                // Show Close button
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
        modifier = Modifier.widthIn(min = 500.dp, max = 700.dp)
    )
}

@Composable
private fun ToolCallStatusSection(toolCall: ToolCall) {
    val (statusText, statusColor) = when (toolCall.status) {
        ToolCallStatus.PENDING -> "Pending" to MaterialTheme.colorScheme.tertiary
        ToolCallStatus.AWAITING_APPROVAL -> "Awaiting Approval" to MaterialTheme.colorScheme.secondary
        ToolCallStatus.EXECUTING -> "Executing" to MaterialTheme.colorScheme.primary
        ToolCallStatus.SUCCESS -> "Success" to MaterialTheme.colorScheme.primary
        ToolCallStatus.ERROR -> "Error" to MaterialTheme.colorScheme.error
        ToolCallStatus.USER_DENIED -> "Denied by User" to MaterialTheme.colorScheme.tertiary
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
                SelectionContainer {
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
            SelectionContainer {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun ToolCallDenialReasonSection(denialReason: String) {
    Column {
        Text(
            text = "Denial Reason",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
        Spacer(Modifier.height(4.dp))

        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = denialReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

