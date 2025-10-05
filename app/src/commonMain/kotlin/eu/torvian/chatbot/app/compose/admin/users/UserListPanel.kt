package eu.torvian.chatbot.app.compose.admin.users

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ScrollbarWrapper
import eu.torvian.chatbot.common.models.user.UserWithDetails

/**
 * Displays a scrollable list of users with a header showing the user count.
 *
 * This panel shows all users in a scrollable list on the left side of the
 * master-detail layout. It includes a header with the user count and handles
 * empty states gracefully.
 *
 * Note: Users are created through registration, not from this UI, so there
 * is no "Add User" button.
 *
 * @param users The list of users to display
 * @param selectedUser The currently selected user, or null if none selected
 * @param onUserSelected Callback invoked when a user is selected
 * @param modifier Modifier for styling and layout
 */
@Composable
fun UserListPanel(
    users: List<UserWithDetails>,
    selectedUser: UserWithDetails?,
    onUserSelected: (UserWithDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Users",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "${users.size} user${if (users.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // List or empty state
            if (users.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No users found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val listState = rememberLazyListState()
                ScrollbarWrapper(listState = listState) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(users, key = { it.id }) { user ->
                            UserListItem(
                                user = user,
                                isSelected = selectedUser?.id == user.id,
                                onClick = { onUserSelected(user) }
                            )
                        }
                    }
                }
            }
        }
    }
}

