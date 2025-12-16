package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState

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

        ChatAreaDialogState.None -> { /* No dialog to show */
        }
    }
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
