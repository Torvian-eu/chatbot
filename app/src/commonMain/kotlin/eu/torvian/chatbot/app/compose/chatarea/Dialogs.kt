package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.domain.contracts.ChatAreaActions
import eu.torvian.chatbot.common.models.ChatMessage

/**
 * Composable for managing all dialogs in the chat area.
 *
 * @param dialogState The current dialog state.
 * @param actions The actions contract for the chat area.
 * @param onDialogStateChange Callback for changing the dialog state.
 */
@Composable
fun Dialogs(
    dialogState: DialogState,
    actions: ChatAreaActions,
    onDialogStateChange: (DialogState) -> Unit
) {
    when (dialogState) {
        is DialogState.DeleteMessage -> {
            DeleteMessageDialog(
                message = dialogState.message,
                onConfirmDelete = {
                    actions.onDeleteMessage(dialogState.message.id)
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        DialogState.None -> { /* No dialog to show */
        }
    }
}

/**
 * Composable for displaying a delete confirmation dialog for a message.
 *
 * @param message The message for which deletion is being confirmed.
 * @param onConfirmDelete Callback for the confirm delete action.
 * @param onDismiss Callback for the dismiss action.
 */
@Composable
fun DeleteMessageDialog(
    message: ChatMessage,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Deletion") },
        text = { Text("Are you sure you want to delete this message? This action cannot be undone.") },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmDelete()
                }
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        },
        modifier = Modifier.padding(16.dp)
    )
}