package eu.torvian.chatbot.app.compose.admin.users

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.settings.DetailRow
import eu.torvian.chatbot.common.models.Role
import eu.torvian.chatbot.common.models.UserGroup
import eu.torvian.chatbot.common.models.UserWithDetails

/**
 * Displays detailed information about a selected user with action buttons.
 *
 * Shows user information including username, email, status, roles, and groups.
 * Provides action buttons for editing, deleting, managing roles, changing password,
 * and changing user status.
 *
 * @param user The user to display, or null if no user is selected
 * @param onEditUser Callback invoked when the edit button is clicked
 * @param onDeleteUser Callback invoked when the delete button is clicked
 * @param onManageRoles Callback invoked when the manage roles button is clicked
 * @param onChangePassword Callback invoked when the change password button is clicked
 * @param onChangeStatus Callback invoked when the change status button is clicked
 * @param onChangePasswordChangeRequired Callback invoked when the change password requirement button is clicked
 * @param modifier Modifier for styling and layout
 */
@Composable
fun UserDetailPanel(
    user: UserWithDetails?,
    onEditUser: (UserWithDetails) -> Unit,
    onDeleteUser: (UserWithDetails) -> Unit,
    onManageRoles: (UserWithDetails) -> Unit,
    onChangePassword: (UserWithDetails) -> Unit,
    onChangeStatus: (UserWithDetails) -> Unit,
    onChangePasswordChangeRequired: (UserWithDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        if (user == null) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Select a user to view details",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header with Edit/Delete buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "User Details",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { onEditUser(user) }) {
                            Icon(Icons.Default.Edit, "Edit user")
                        }
                        IconButton(onClick = { onDeleteUser(user) }) {
                            Icon(
                                Icons.Default.Delete,
                                "Delete user",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Scrollable content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    UserDetailsContent(
                        user = user,
                        onManageRoles = onManageRoles,
                        onChangePassword = onChangePassword,
                        onChangeStatus = onChangeStatus,
                        onChangePasswordChangeRequired = onChangePasswordChangeRequired
                    )
                }
            }
        }
    }
}

/**
 * Displays the detailed content of a user including basic information,
 * roles, groups, and action buttons.
 *
 * @param user The user to display
 * @param onManageRoles Callback invoked when the manage roles button is clicked
 * @param onChangePassword Callback invoked when the change password button is clicked
 * @param onChangeStatus Callback invoked when the change status button is clicked
 */
@Composable
private fun UserDetailsContent(
    user: UserWithDetails,
    onManageRoles: (UserWithDetails) -> Unit,
    onChangePassword: (UserWithDetails) -> Unit,
    onChangeStatus: (UserWithDetails) -> Unit,
    onChangePasswordChangeRequired: (UserWithDetails) -> Unit
) {
    // Basic Information Section
    Text(
        text = "Basic Information",
        style = MaterialTheme.typography.titleMedium
    )

    DetailRow("Username", user.username)
    DetailRow("Email", user.email ?: "Not provided")
    DetailRow("Status", user.status.name)
    DetailRow("User ID", user.id.toString())
    DetailRow("Created", user.createdAt.toString())
    DetailRow("Last Login", user.lastLogin?.toString() ?: "Never")
    DetailRow("Requires Password Change", if (user.requiresPasswordChange) "Yes" else "No")

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Roles Section
    Text(
        text = "Roles (${user.roles.size})",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (user.roles.isEmpty()) {
        Text(
            text = "No roles assigned",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            user.roles.forEach { role ->
                RoleChip(role = role)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Button(onClick = { onManageRoles(user) }) {
        Text("Manage Roles")
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Groups Section
    Text(
        text = "Groups (${user.userGroups.size})",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (user.userGroups.isEmpty()) {
        Text(
            text = "No groups assigned",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            user.userGroups.forEach { group ->
                GroupChip(group = group)
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Actions Section
    Text(
        text = "Actions",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(8.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onChangePassword(user) }) {
                Text("Change Password")
            }
            Button(onClick = { onChangeStatus(user) }) {
                Text("Change Status")
            }
        }
        Button(
            onClick = { onChangePasswordChangeRequired(user) }
        ) {
            Text("Change Password Requirement")
        }
    }
}

/**
 * Displays a chip for a role.
 *
 * @param role The role to display
 */
@Composable
private fun RoleChip(role: Role) {
    SuggestionChip(
        onClick = { },
        label = { Text(role.name) }
    )
}

/**
 * Displays a chip for a user group.
 *
 * @param group The user group to display
 */
@Composable
private fun GroupChip(group: UserGroup) {
    SuggestionChip(
        onClick = { },
        label = { Text(group.name) }
    )
}
