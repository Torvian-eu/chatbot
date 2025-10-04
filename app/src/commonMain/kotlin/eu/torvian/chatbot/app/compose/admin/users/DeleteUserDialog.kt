package eu.torvian.chatbot.app.compose.admin.users

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.UserWithDetails

/**
 * Confirmation dialog for deleting a user.
 *
 * Displays a warning message and requires the user to confirm the deletion.
 * The action is styled as destructive with a red button to emphasize the
 * irreversible nature of the operation.
 *
 * @param user The user to delete
 * @param onDismiss Callback invoked when the dialog is dismissed
 * @param onConfirm Callback invoked when the delete button is clicked
 */
@Composable
fun DeleteUserDialog(
    user: UserWithDetails,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text("Delete User?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Are you sure you want to delete user '${user.username}'?")
                Text(
                    "This action cannot be undone. All user data will be permanently deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
