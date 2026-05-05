package eu.torvian.chatbot.app.compose.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.common.models.api.auth.UserSessionInfo
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Dialog that shows the authenticated user's server-side sessions and allows revoking older devices.
 *
 * The dialog intentionally keeps the current session non-revocable so the user can inspect their
 * login footprint without risking immediate self-lockout.
 *
 * @param sessions The sessions loaded from the backend in newest-first order.
 * @param currentAuthState The current authentication state used to identify the active session.
 * @param onDismiss Called when the dialog should be closed.
 * @param onRevokeSession Called when the user requests revocation of a non-current session.
 */
@Composable
fun ActiveSessionsDialog(
    sessions: List<UserSessionInfo>,
    currentAuthState: AuthState,
    onDismiss: () -> Unit,
    onRevokeSession: (Long) -> Unit
) {
    val currentSessionId = if (currentAuthState is AuthState.Authenticated) {
        sessions.firstOrNull { session -> session.isCurrentSession }?.sessionId
    } else {
        null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Active Sessions",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "Review where your account is signed in and revoke anything unfamiliar.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close active sessions dialog"
                        )
                    }
                }

                HorizontalDivider()

                if (sessions.isEmpty()) {
                    Text(
                        text = "No active sessions were returned by the server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(sessions, key = { it.sessionId }) { session ->
                            ActiveSessionCard(
                                session = session,
                                isCurrentSession = session.isCurrentSession || session.sessionId == currentSessionId,
                                onRevokeSession = { onRevokeSession(session.sessionId) }
                            )
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Close")
                }
            }
        }
    }
}

/**
 * Renders a single active session row with status, timing, and revocation controls.
 *
 * @param session The session to display.
 * @param isCurrentSession Whether the session matches the currently authenticated request.
 * @param onRevokeSession Called when the user wants to revoke this session.
 */
@Composable
private fun ActiveSessionCard(
    session: UserSessionInfo,
    isCurrentSession: Boolean,
    onRevokeSession: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isCurrentSession) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = session.ipAddress ?: "Unknown IP address",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = "Last accessed ${formatRelativeTime(session.lastAccessed)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!isCurrentSession) {
                    IconButton(onClick = onRevokeSession) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Revoke session",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCurrentSession) {
                    SessionBadge(text = "Current Session")
                }
                SessionBadge(text = "Session #${session.sessionId}")
            }
        }
    }
}

/**
 * Displays a lightweight status badge for session metadata.
 *
 * @param text The label shown inside the badge.
 */
@Composable
private fun SessionBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Formats a timestamp as a human-friendly relative time string.
 *
 * @param instant The point in time to describe.
 * @return A short relative description such as "Just now" or "5 minutes ago".
 */
private fun formatRelativeTime(instant: Instant): String {
    val duration = Clock.System.now() - instant

    return when {
        duration < 1.minutes -> "Just now"
        duration < 1.hours -> "${duration.inWholeMinutes} minute${pluralSuffix(duration.inWholeMinutes)} ago"
        duration < 1.days -> "${duration.inWholeHours} hour${pluralSuffix(duration.inWholeHours)} ago"
        else -> "${duration.inWholeDays} day${pluralSuffix(duration.inWholeDays)} ago"
    }
}

/**
 * Returns a plural suffix for small relative-time labels.
 *
 * @param value The numeric quantity being rendered.
 * @return An empty string for singular values, otherwise "s".
 */
private fun pluralSuffix(value: Long): String = if (value == 1L) "" else "s"


