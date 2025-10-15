package eu.torvian.chatbot.app.compose.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.common.api.CommonPermissions
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Dialog for switching between available user accounts.
 *
 * Displays all stored accounts with their details, allowing the user to
 * switch to a different account or remove accounts.
 */
@Composable
fun SwitchAccountDialog(
    availableAccounts: List<AccountData>,
    currentAuthState: AuthState,
    accountSwitchInProgress: Boolean,
    onDismiss: () -> Unit,
    onSwitchAccount: (Long) -> Unit,
    onRemoveAccount: (AccountData) -> Unit
) {
    val currentUserId = if (currentAuthState is AuthState.Authenticated) {
        currentAuthState.userId
    } else {
        null
    }

    // Sort accounts by last used (most recent first)
    val sortedAccounts = availableAccounts.sortedByDescending { it.lastUsed }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch Account") },
        text = {
            if (sortedAccounts.isEmpty()) {
                Text("No accounts available.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sortedAccounts) { account ->
                        AccountItem(
                            account = account,
                            isCurrentAccount = account.user.id == currentUserId,
                            accountSwitchInProgress = accountSwitchInProgress,
                            onSwitchAccount = { onSwitchAccount(account.user.id) },
                            onRemoveAccount = { onRemoveAccount(account) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Individual account item in the switch account dialog.
 */
@Composable
private fun AccountItem(
    account: AccountData,
    isCurrentAccount: Boolean,
    accountSwitchInProgress: Boolean,
    onSwitchAccount: () -> Unit,
    onRemoveAccount: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isCurrentAccount) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )

                    Column {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = account.user.username,
                                style = MaterialTheme.typography.titleMedium
                            )

                            if (isCurrentAccount) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Active account",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Text(
                            text = formatLastUsed(account.lastUsed, isCurrentAccount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Show admin badge if user has admin permissions
                        if (hasAdminPermissions(account)) {
                            Text(
                                text = "Admin",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Remove button
                IconButton(
                    onClick = onRemoveAccount,
                    enabled = !accountSwitchInProgress
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove account",
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                // Switch button (disabled if current account)
                Button(
                    onClick = onSwitchAccount,
                    enabled = !isCurrentAccount && !accountSwitchInProgress,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Switch")
                }
            }
        }
    }
}

/**
 * Formats the last used timestamp as a relative time string.
 */
private fun formatLastUsed(lastUsed: kotlinx.datetime.Instant, isCurrentAccount: Boolean): String {
    if (isCurrentAccount) {
        return "Active"
    }

    val now = Clock.System.now()
    val duration = now - lastUsed

    return when {
        duration < 1.minutes -> "Just now"
        duration < 1.hours -> "${duration.inWholeMinutes} minute${if (duration.inWholeMinutes != 1L) "s" else ""} ago"
        duration < 1.days -> "${duration.inWholeHours} hour${if (duration.inWholeHours != 1L) "s" else ""} ago"
        else -> "${duration.inWholeDays} day${if (duration.inWholeDays != 1L) "s" else ""} ago"
    }
}

/**
 * Checks if the account has admin permissions.
 */
private fun hasAdminPermissions(account: AccountData): Boolean {
    return account.permissions.any { permission ->
        (permission.action == CommonPermissions.MANAGE_USERS.action &&
         permission.subject == CommonPermissions.MANAGE_USERS.subject) ||
        (permission.action == CommonPermissions.MANAGE_ROLES.action &&
         permission.subject == CommonPermissions.MANAGE_ROLES.subject)
    }
}
