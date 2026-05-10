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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.torvian.chatbot.app.compose.common.StatusBadge
import eu.torvian.chatbot.app.utils.ui.formatRelativeTime
import eu.torvian.chatbot.common.models.api.auth.UserTrustedDeviceInfo

/**
 * Dialog that shows the authenticated user's trusted devices and allows revoking them.
 *
 * The dialog intentionally keeps the current device non-revocable so the user can inspect their
 * trusted devices without risking immediate self-lockout. Users should use 'Logout' for that.
 *
 * @param devices The trusted devices loaded from the backend.
 * @param currentDeviceId The device ID of the current session (to identify the current device).
 * @param isCurrentSessionRestricted Whether the current session is restricted (IP not verified).
 * @param onDismiss Called when the dialog should be closed.
 * @param onRevokeDevice Called when the user requests revocation of a non-current device.
 * @param onCopyToClipboard Called when the user wants to copy text to the clipboard.
 */
@Composable
fun TrustedDevicesDialog(
    devices: List<UserTrustedDeviceInfo>,
    currentDeviceId: String?,
    isCurrentSessionRestricted: Boolean,
    onDismiss: () -> Unit,
    onRevokeDevice: (String) -> Unit,
    onCopyToClipboard: (String) -> Unit
) {
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
                            text = "Trusted Devices",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "Review devices that can access your account without verification.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close trusted devices dialog"
                        )
                    }
                }

                HorizontalDivider()

                if (devices.isEmpty()) {
                    Text(
                        text = "No trusted devices were returned by the server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(devices, key = { it.deviceId }) { device ->
                            TrustedDeviceCard(
                                device = device,
                                isCurrentDevice = device.deviceId == currentDeviceId,
                                isRevokeDisabled = isCurrentSessionRestricted,
                                onRevokeDevice = { onRevokeDevice(device.deviceId) },
                                onCopyToClipboard = onCopyToClipboard
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
 * Renders a single trusted device row with status, timing, and revocation controls.
 *
 * @param device The device to display.
 * @param isCurrentDevice Whether this device matches the current session's device.
 * @param isRevokeDisabled Whether the revoke action should be disabled (for restricted sessions).
 * @param onRevokeDevice Called when the user wants to revoke this device.
 * @param onCopyToClipboard Called when the user wants to copy text to the clipboard.
 */
@Composable
private fun TrustedDeviceCard(
    device: UserTrustedDeviceInfo,
    isCurrentDevice: Boolean,
    isRevokeDisabled: Boolean,
    onRevokeDevice: () -> Unit,
    onCopyToClipboard: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isCurrentDevice) {
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
                        imageVector = Icons.Default.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = device.lastIpAddress ?: "Unknown IP address",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = "Last used ${formatRelativeTime(device.lastUsedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Show delete button only for non-current devices and when not disabled
                    if (!isCurrentDevice) {
                        IconButton(
                            onClick = onRevokeDevice,
                            enabled = !isRevokeDisabled
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = if (isRevokeDisabled) {
                                    "Revoke device (disabled in restricted sessions)"
                                } else {
                                    "Revoke device"
                                },
                                tint = if (isRevokeDisabled) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                }
            }

            // Device ID row with copy button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Device ID: ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = device.deviceId.take(8) + "...",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = { onCopyToClipboard(device.deviceId) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy device ID to clipboard",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCurrentDevice) {
                    StatusBadge(text = "Current Device")
                }
                StatusBadge(text = "First seen ${formatRelativeTime(device.firstSeenAt)}")
            }
        }
    }
}
