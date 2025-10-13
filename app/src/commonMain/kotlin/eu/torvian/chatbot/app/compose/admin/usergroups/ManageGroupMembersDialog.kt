package eu.torvian.chatbot.app.compose.admin.usergroups

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserGroup

/**
 * Dialog for managing members of a user group.
 *
 * Displays the current members of the group and allows adding new members
 * from a dropdown of available users. Members can be removed from the group.
 *
 * @param group The group whose members are being managed
 * @param members The current list of members in the group
 * @param availableUsers All users in the system (for adding new members)
 * @param isLoading Whether a member operation is currently in progress
 * @param onDismiss Callback invoked when the dialog is dismissed
 * @param onAddMember Callback invoked when a user should be added to the group
 * @param onRemoveMember Callback invoked when a user should be removed from the group
 */
@Composable
fun ManageGroupMembersDialog(
    group: UserGroup,
    members: List<User>,
    availableUsers: List<User>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onAddMember: (User) -> Unit,
    onRemoveMember: (User) -> Unit
) {
    var showAddMemberDropdown by remember { mutableStateOf(false) }
    
    // Filter out users who are already members
    val usersToAdd = availableUsers.filter { user ->
        members.none { it.id == user.id }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Members: ${group.name}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().height(400.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Add member section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Members (${members.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    if (usersToAdd.isNotEmpty()) {
                        Box {
                            IconButton(
                                onClick = { showAddMemberDropdown = true },
                                enabled = !isLoading
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Member")
                            }
                            
                            DropdownMenu(
                                expanded = showAddMemberDropdown,
                                onDismissRequest = { showAddMemberDropdown = false }
                            ) {
                                usersToAdd.forEach { user ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Person, contentDescription = null)
                                                Column {
                                                    Text(user.username)
                                                    user.email?.let { email ->
                                                        Text(
                                                            email,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        onClick = {
                                            onAddMember(user)
                                            showAddMemberDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Members list
                if (members.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No members in this group",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(members, key = { it.id }) { member ->
                            MemberListItem(
                                user = member,
                                isLoading = isLoading,
                                onRemove = { onRemoveMember(member) }
                            )
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

/**
 * List item for displaying a group member with a remove button.
 *
 * @param user The user to display
 * @param isLoading Whether operations are in progress
 * @param onRemove Callback invoked when the remove button is clicked
 */
@Composable
private fun MemberListItem(
    user: User,
    isLoading: Boolean,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = user.username,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    user.email?.let { email ->
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            IconButton(
                onClick = onRemove,
                enabled = !isLoading
            ) {
                Icon(
                    Icons.Default.PersonRemove,
                    contentDescription = "Remove from group",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

