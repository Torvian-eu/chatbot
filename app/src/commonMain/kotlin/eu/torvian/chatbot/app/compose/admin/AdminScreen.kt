package eu.torvian.chatbot.app.compose.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.admin.users.UserManagementTabRoute
import eu.torvian.chatbot.app.compose.permissions.RequiresAllPermissions
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.common.api.CommonPermissions

/**
 * Admin screen with tabs for managing users, roles, permissions, and user groups.
 *
 * This screen serves as the main entry point for all administrative functions.
 * Currently implements the Users tab, with placeholders for future tabs.
 *
 * **Required Permissions:** MANAGE_USERS and MANAGE_ROLES
 *
 * @param authState The current authentication state (passed from MainApplicationFlow)
 * @param modifier Modifier for styling and layout
 */
@Composable
fun AdminScreen(
    authState: AuthState.Authenticated,
    modifier: Modifier = Modifier
) {
    var selectedTabIndex by remember { mutableStateOf(0) }

    // Tab titles - future tabs will be added here
    val tabTitles = listOf(
        "Users",
        "Roles",          // TODO: Implement RoleManagementTab
        "Permissions",    // TODO: Implement PermissionManagementTab
        "User Groups"     // TODO: Implement UserGroupManagementTab
    )

    Column(modifier = modifier.fillMaxSize()) {
        // Tab Row
        TabRow(selectedTabIndex = selectedTabIndex) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) }
                )
            }
        }

        // Tab Content
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when (selectedTabIndex) {
                0 -> {
                    // Users Tab - requires MANAGE_USERS and MANAGE_ROLES
                    RequiresAllPermissions(
                        authState = authState,
                        permissions = listOf(
                            CommonPermissions.MANAGE_USERS,
                            CommonPermissions.MANAGE_ROLES
                        )
                    ) {
                        UserManagementTabRoute(authState = authState)
                    }
                }
                1 -> {
                    // Roles Tab - TODO: Implement
                    PlaceholderTab("Roles Management", "Coming soon...")
                }
                2 -> {
                    // Permissions Tab - TODO: Implement
                    PlaceholderTab("Permission Management", "Coming soon...")
                }
                3 -> {
                    // User Groups Tab - TODO: Implement
                    PlaceholderTab("User Group Management", "Coming soon...")
                }
            }
        }
    }
}

/**
 * Placeholder composable for future admin tabs.
 *
 * Displays a centered message indicating that the feature is coming soon.
 *
 * @param title The title of the placeholder tab
 * @param message The message to display
 */
@Composable
private fun PlaceholderTab(title: String, message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
