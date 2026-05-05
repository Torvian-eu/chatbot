package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import eu.torvian.chatbot.app.service.auth.AccountData

/**
 * User menu dropdown for authenticated users.
 *
 * The menu exposes account switching, account creation, and logout actions that are driven by the
 * surrounding authentication flow.
 */
@Composable
fun UserMenu(
    username: String,
    availableAccounts: List<AccountData>,
    accountSwitchInProgress: Boolean,
    onSwitchAccount: () -> Unit,
    onAddAccount: () -> Unit,
    onLogout: () -> Unit,
    onLogoutAll: () -> Unit,
    onLogin: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "User menu"
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // User info header
            DropdownMenuItem(
                text = {
                    Text(
                        text = username,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                onClick = { /* Future: navigate to profile */ },
                leadingIcon = {
                    Icon(Icons.Default.AccountCircle, contentDescription = null)
                }
            )

            HorizontalDivider()

            // Add Account option
            DropdownMenuItem(
                text = { Text("Add Account") },
                onClick = {
                    expanded = false
                    onLogin()
//                    onAddAccount()
                },
                leadingIcon = {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                },
                enabled = !accountSwitchInProgress
            )

            // Logout all sessions option
            DropdownMenuItem(
                text = { Text("Logout all sessions") },
                onClick = {
                    expanded = false
                    onLogoutAll()
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                },
                enabled = !accountSwitchInProgress
            )

            // Switch Account option (only show if multiple accounts available)
            if (availableAccounts.size > 1) {
                DropdownMenuItem(
                    text = { Text("Switch Account") },
                    onClick = {
                        expanded = false
                        onSwitchAccount()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                    },
                    enabled = !accountSwitchInProgress
                )
            }

            HorizontalDivider()

            // Logout option
            DropdownMenuItem(
                text = { Text("Logout") },
                onClick = {
                    expanded = false
                    onLogout()
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                }
            )
        }
    }
}