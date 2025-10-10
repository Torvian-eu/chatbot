package eu.torvian.chatbot.app.compose.admin.usergroups

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.user.UserGroup

/**
 * Confirmation dialog for deleting a user group.
 *
 * Displays a warning message and requires the user to confirm the deletion.
 * The action is styled as destructive with a red button to emphasize the
 * irreversible nature of the operation.
 *
 * @param group The group to delete
 * @param onDismiss Callback invoked when the dialog is dismissed
 * @param onConfirm Callback invoked when the delete button is clicked
 */
@Composable
fun DeleteGroupDialog(
    group: UserGroup,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text("Delete User Group?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Are you sure you want to delete the group '${group.name}'?")
                Text(
                    "This action cannot be undone. Resources shared with this group will no longer be accessible to its members.",
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

