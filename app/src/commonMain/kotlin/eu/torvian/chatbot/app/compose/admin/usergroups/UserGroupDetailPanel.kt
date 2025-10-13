package eu.torvian.chatbot.app.compose.admin.usergroups

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.settings.DetailRow
import eu.torvian.chatbot.common.models.user.UserGroup

/**
 * Displays detailed information about a selected user group with action buttons.
 *
 * Shows group information including name and description.
 * Provides action buttons for editing, deleting, and managing members.
 *
 * @param group The group to display, or null if no group is selected
 * @param onEditGroup Callback invoked when the edit button is clicked
 * @param onDeleteGroup Callback invoked when the delete button is clicked
 * @param onManageMembers Callback invoked when the manage members button is clicked
 * @param modifier Modifier for styling and layout
 */
@Composable
fun UserGroupDetailPanel(
    group: UserGroup?,
    onEditGroup: (UserGroup) -> Unit,
    onDeleteGroup: (UserGroup) -> Unit,
    onManageMembers: (UserGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        if (group == null) {
            // Empty state - no group selected
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Select a group to view details",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                        text = "Group Details",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { onEditGroup(group) }) {
                            Icon(Icons.Default.Edit, "Edit group")
                        }
                        IconButton(onClick = { onDeleteGroup(group) }) {
                            Icon(
                                Icons.Default.Delete,
                                "Delete group",
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
                    UserGroupDetailsContent(
                        group = group,
                        onManageMembers = onManageMembers
                    )
                }
            }
        }
    }
}

/**
 * Displays the detailed content of a user group including basic information and action buttons.
 *
 * @param group The group to display
 * @param onManageMembers Callback invoked when the manage members button is clicked
 */
@Composable
private fun UserGroupDetailsContent(
    group: UserGroup,
    onManageMembers: (UserGroup) -> Unit
) {
    // Basic Information Section
    Text(
        text = "Basic Information",
        style = MaterialTheme.typography.titleMedium
    )

    DetailRow(
        label = "Name",
        value = group.name
    )

    DetailRow(
        label = "Description",
        value = group.description ?: "No description"
    )

    DetailRow(
        label = "Group ID",
        value = group.id.toString()
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Actions Section
    Text(
        text = "Actions",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(8.dp))

    Button(onClick = { onManageMembers(group) }) {
        Icon(Icons.Default.Group, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Manage Members")
    }
}
