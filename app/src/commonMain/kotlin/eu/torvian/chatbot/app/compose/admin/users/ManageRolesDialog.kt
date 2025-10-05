package eu.torvian.chatbot.app.compose.admin.users

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.user.Role
import eu.torvian.chatbot.common.models.user.UserWithDetails

/**
 * Dialog for managing a user's role assignments.
 *
 * Displays a list of all available roles with assign/revoke buttons.
 * Shows which roles are currently assigned to the user and allows
 * administrators to assign new roles or revoke existing ones.
 *
 * @param user The user whose roles are being managed
 * @param availableRoles The list of all available roles in the system
 * @param isLoading Whether a role operation is currently in progress
 * @param onDismiss Callback invoked when the dialog is dismissed
 * @param onAssignRole Callback invoked when a role should be assigned
 * @param onRevokeRole Callback invoked when a role should be revoked
 */
@Composable
fun ManageRolesDialog(
    user: UserWithDetails,
    availableRoles: List<Role>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onAssignRole: (Role) -> Unit,
    onRevokeRole: (Role) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Roles for ${user.username}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (availableRoles.isEmpty()) {
                    Text(
                        text = "No roles available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    availableRoles.forEach { role ->
                        val isAssigned = user.roles.any { it.id == role.id }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(role.name, style = MaterialTheme.typography.bodyLarge)
                                role.description?.let { description ->
                                    Text(
                                        description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            if (isAssigned) {
                                Button(
                                    onClick = { onRevokeRole(role) },
                                    enabled = !isLoading,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Revoke")
                                }
                            } else {
                                Button(
                                    onClick = { onAssignRole(role) },
                                    enabled = !isLoading
                                ) {
                                    Text("Assign")
                                }
                            }
                        }

                        if (role != availableRoles.last()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Close")
            }
        }
    )
}

