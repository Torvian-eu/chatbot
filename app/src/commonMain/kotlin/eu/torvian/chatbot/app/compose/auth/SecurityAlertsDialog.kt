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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
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
import eu.torvian.chatbot.common.models.api.auth.UserSecurityAlert
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Dialog that displays unacknowledged security alerts for the current user.
 *
 * This dialog shows login attempts from unrecognized IP addresses and allows
 * the user to acknowledge them. In restricted sessions (new location login),
 * the acknowledge button is disabled with an explanatory message.
 *
 * @param alerts The list of unacknowledged security alerts to display.
 * @param isRestricted Whether the current session is restricted (created from an unacknowledged IP).
 * @param onDismiss Called when the dialog should be closed.
 * @param onAcknowledge Called when the user clicks the acknowledge button (only enabled for non-restricted sessions).
 */
@Composable
fun SecurityAlertsDialog(
    alerts: List<UserSecurityAlert>,
    isRestricted: Boolean,
    onDismiss: () -> Unit,
    onAcknowledge: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                            text = "Security Alerts",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "Review unrecognized login attempts from new locations.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close security alerts dialog"
                        )
                    }
                }

                // Show restricted session warning if applicable
                if (isRestricted) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Restricted Session",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "This session is restricted because you logged in from a new location. To clear this alert, please verify your IP via the link sent to your email or approve it from a trusted device.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                if (alerts.isEmpty()) {
                    Text(
                        text = "No security alerts to display.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(alerts, key = { it.id }) { alert ->
                            SecurityAlertCard(alert = alert)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }

                    Button(
                        onClick = onAcknowledge,
                        enabled = !isRestricted && alerts.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (isRestricted) "Cannot Acknowledge" else "It was me",
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders a single security alert card showing IP address and login time.
 *
 * @param alert The security alert to display.
 */
@Composable
private fun SecurityAlertCard(alert: UserSecurityAlert) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
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
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = alert.ipAddress,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.error
                        )

                        Text(
                            text = "First seen ${formatRelativeTime(alert.firstSeenAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (alert.lastSeenAt != alert.firstSeenAt) {
                            Text(
                                text = "Last seen ${formatRelativeTime(alert.lastSeenAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlertBadge(text = "Unacknowledged")
            }
        }
    }
}

/**
 * Displays a lightweight status badge for alert metadata.
 *
 * @param text The label shown inside the badge.
 */
@Composable
private fun AlertBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
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
