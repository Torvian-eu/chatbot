package eu.torvian.chatbot.app.compose.admin.users

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.UserStatus
import eu.torvian.chatbot.common.models.UserWithDetails

/**
 * Dialog for changing a user's account status.
 *
 * Provides radio buttons to select between ACTIVE, DISABLED, and LOCKED states.
 * Includes descriptions for each status and a warning when selecting a non-active status.
 *
 * @param user The user whose status is being changed
 * @param isLoading Whether the status change operation is in progress
 * @param onDismiss Callback invoked when the dialog is dismissed
 * @param onConfirm Callback invoked when the change status button is clicked with the selected status
 */
@Composable
fun ChangeUserStatusDialog(
    user: UserWithDetails,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (UserStatus) -> Unit
) {
    var selectedStatus by remember { mutableStateOf(user.status) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Status for ${user.username}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Current status: ${user.status.name}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Select new status:",
                    style = MaterialTheme.typography.titleSmall
                )

                // Status options
                UserStatus.entries.forEach { status ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isLoading) { selectedStatus = status }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedStatus == status,
                            onClick = { selectedStatus = status },
                            enabled = !isLoading
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(status.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when (status) {
                                    UserStatus.ACTIVE -> "User can log in and use the application"
                                    UserStatus.DISABLED -> "User cannot log in (temporary)"
                                    UserStatus.LOCKED -> "User account is locked (security)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (selectedStatus != UserStatus.ACTIVE) {
                    Text(
                        "Warning: Changing status will affect user's ability to log in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedStatus) },
                enabled = !isLoading && selectedStatus != user.status
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Change Status")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
            }
        }
    )
}

