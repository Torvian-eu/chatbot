package eu.torvian.chatbot.app.compose.admin.usergroups

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ScrollbarWrapper
import eu.torvian.chatbot.common.models.user.UserGroup

/**
 * Displays a scrollable list of user groups with a header showing the group count.
 *
 * This panel shows all groups in a scrollable list on the left side of the
 * master-detail layout. It includes a header with the group count and a button
 * to create new groups.
 *
 * @param groups The list of groups to display
 * @param selectedGroup The currently selected group, or null if none selected
 * @param onGroupSelected Callback invoked when a group is selected
 * @param onCreateGroup Callback invoked when the create group button is clicked
 * @param modifier Modifier for styling and layout
 */
@Composable
fun UserGroupListPanel(
    groups: List<UserGroup>,
    selectedGroup: UserGroup?,
    onGroupSelected: (UserGroup) -> Unit,
    onCreateGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header with count and create button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "User Groups (${groups.size})",
                    style = MaterialTheme.typography.titleLarge
                )

                IconButton(onClick = onCreateGroup) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Create Group",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // List or empty state
            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No user groups found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onCreateGroup) {
                            Text("Create your first group")
                        }
                    }
                }
            } else {
                val listState = rememberLazyListState()
                ScrollbarWrapper(listState = listState) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(groups, key = { it.id }) { group ->
                            UserGroupListItem(
                                group = group,
                                isSelected = selectedGroup?.id == group.id,
                                onClick = { onGroupSelected(group) }
                            )
                        }
                    }
                }
            }
        }
    }
}

