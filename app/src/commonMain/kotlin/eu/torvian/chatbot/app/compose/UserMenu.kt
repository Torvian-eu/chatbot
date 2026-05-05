package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.torvian.chatbot.app.service.auth.AccountData

/**
 * User menu dropdown for authenticated users.
 *
 * The menu exposes account switching, account creation, active-session management, and logout
 * actions that are driven by the surrounding authentication flow.
 */
@Composable
fun UserMenu(
    username: String,
    availableAccounts: List<AccountData>,
    accountSwitchInProgress: Boolean,
    onSwitchAccount: () -> Unit,
    onActiveSessions: () -> Unit,
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
            // User info header.
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

            // Active session management opens a dedicated dialog instead of navigating away.
            DropdownMenuItem(
                text = { Text("Active Sessions") },
                onClick = {
                    expanded = false
                    onActiveSessions()
                },
                leadingIcon = {
                    Icon(Icons.Default.Security, contentDescription = null)
                }
            )

            // Logout all sessions affects every device, so keep it grouped with the security actions.
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

            // The existing flow still navigates to the login screen for adding another account.
            DropdownMenuItem(
                text = { Text("Add Account") },
                onClick = {
                    expanded = false
                    onLogin()
                },
                leadingIcon = {
                    Icon(Icons.Default.AccountCircle, contentDescription = null)
                },
                enabled = !accountSwitchInProgress
            )

            // Current-session logout stays last so the more explicit security actions appear first.
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