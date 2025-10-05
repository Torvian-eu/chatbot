package eu.torvian.chatbot.app.compose.admin.users

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.user.UserWithDetails

/**
 * Dialog for changing a user's password change required flag.
 *
 * Provides radio buttons to select between requiring or not requiring a password change.
 *
 * @param user The user whose password change required flag is being changed
 * @param isLoading Whether the operation is in progress
 * @param onDismiss Callback invoked when the dialog is dismissed
 * @param onConfirm Callback invoked when the confirm button is clicked with the selected value
 */
@Composable
fun ChangePasswordChangeRequiredDialog(
    user: UserWithDetails,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit
) {
    var selectedValue by remember { mutableStateOf(user.requiresPasswordChange) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Password Requirement for ${user.username}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Current setting: ${if (user.requiresPasswordChange) "Required" else "Not Required"}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Select new setting:",
                    style = MaterialTheme.typography.titleSmall
                )

                // Option: Not Required
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLoading) { selectedValue = false }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !selectedValue,
                        onClick = { selectedValue = false },
                        enabled = !isLoading
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Not Required", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "User can log in normally without changing password",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Option: Required
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLoading) { selectedValue = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedValue,
                        onClick = { selectedValue = true },
                        enabled = !isLoading
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Required", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "User must change password on next login",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (selectedValue) {
                    Text(
                        "Note: User will be prompted to change their password on next login.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedValue) },
                enabled = !isLoading && selectedValue != user.requiresPasswordChange
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Confirm")
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

