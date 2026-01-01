package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Manages displaying dialogs for the Chat Area based on the ViewModel's state.
 *
 * @param dialogState The current dialog state from the ViewModel.
 */
@Composable
fun Dialogs(dialogState: ChatAreaDialogState) {
    when (dialogState) {
        is ChatAreaDialogState.DeleteMessage -> {
            DeleteMessageDialog(
                onDeleteConfirm = dialogState.onDeleteConfirm,
                onDismiss = dialogState.onDismiss
            )
        }

        is ChatAreaDialogState.DeleteMessageRecursively -> {
            DeleteMessageRecursivelyDialog(
                onDeleteConfirm = dialogState.onDeleteConfirm,
                onDismiss = dialogState.onDismiss
            )
        }

        is ChatAreaDialogState.ToolConfig -> {
            // Collect reactive StateFlows for live updates
            val enabledToolsState by dialogState.enabledToolsFlow.collectAsState()
            val availableToolsState by dialogState.availableToolsFlow.collectAsState()
            val mcpServersState by dialogState.mcpServersFlow.collectAsState()

            ToolConfigDialog(
                availableTools = availableToolsState.dataOrNull ?: emptyList(),
                enabledTools = enabledToolsState.dataOrNull ?: emptyList(),
                mcpServers = mcpServersState.dataOrNull ?: emptyList(),
                onToggleTool = dialogState.onToggleTool,
                onToggleTools = dialogState.onToggleTools,
                onDismiss = dialogState.onDismiss
            )
        }

        is ChatAreaDialogState.ToolCallDetails -> {
            ToolCallDetailsDialog(
                toolCall = dialogState.toolCall,
                onDismiss = dialogState.onDismiss,
                onApprove = dialogState.onApprove,
                onDeny = dialogState.onDeny
            )
        }

        is ChatAreaDialogState.InsertMessage -> {
            InsertMessageDialog(
                onConfirm = dialogState.onConfirm,
                onDismiss = dialogState.onDismiss
            )
        }

        is ChatAreaDialogState.FileReferenceDetails -> {
            FileReferenceDetailsDialog(
                fileReference = dialogState.fileReference,
                onDismiss = dialogState.onDismiss
            )
        }

        is ChatAreaDialogState.FileReferencesManagement -> {
            FileReferencesManagementDialog(
                fileReferencesFlow = dialogState.fileReferencesFlow,
                basePathFlow = dialogState.basePathFlow,
                onBasePathChange = dialogState.onBasePathChange,
                onResetBasePath = dialogState.onResetBasePath,
                onAddFiles = dialogState.onAddFiles,
                onRemoveFile = dialogState.onRemoveFile,
                onToggleContent = dialogState.onToggleContent,
                onDismiss = dialogState.onDismiss
            )
        }

        ChatAreaDialogState.None -> {
            // No dialog
        }
    }
}

@Composable
fun InsertMessageDialog(
    onConfirm: (MessageInsertPosition, ChatMessage.Role, String) -> Unit,
    onDismiss: () -> Unit
) {
    var position by remember { mutableStateOf(MessageInsertPosition.BELOW) }
    var role by remember { mutableStateOf(ChatMessage.Role.USER) }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Position:")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = position == MessageInsertPosition.ABOVE,
                        onClick = { position = MessageInsertPosition.ABOVE }
                    )
                    Text("Above")
                    Spacer(Modifier.width(16.dp))
                    RadioButton(
                        selected = position == MessageInsertPosition.BELOW,
                        onClick = { position = MessageInsertPosition.BELOW }
                    )
                    Text("Below")
                    Spacer(Modifier.width(16.dp))
                    RadioButton(
                        selected = position == MessageInsertPosition.APPEND,
                        onClick = { position = MessageInsertPosition.APPEND }
                    )
                    Text("Append")
                }

                Text("Role:")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = role == ChatMessage.Role.USER,
                        onClick = { role = ChatMessage.Role.USER }
                    )
                    Text("User")
                    Spacer(Modifier.width(16.dp))
                    RadioButton(
                        selected = role == ChatMessage.Role.ASSISTANT,
                        onClick = { role = ChatMessage.Role.ASSISTANT }
                    )
                    Text("Assistant")
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(position, role, content) }) {
                Text("Insert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for confirming message deletion.
 */
@Composable
private fun DeleteMessageDialog(
    onDeleteConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Message?") },
        text = { Text("Are you sure you want to delete this message? This action cannot be undone.") },
        confirmButton = {
            Button(
                onClick = onDeleteConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for confirming recursive message deletion (delete thread).
 * Shows a stronger warning since this deletes the message and all replies.
 */
@Composable
private fun DeleteMessageRecursivelyDialog(
    onDeleteConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Thread?") },
        text = {
            Text(
                "This will permanently delete this message and all replies in this thread. " +
                        "This action cannot be undone."
            )
        },
        confirmButton = {
            Button(
                onClick = onDeleteConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete Thread") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
