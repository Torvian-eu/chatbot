package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Content composable for the E2EA Security settings tab.
 *
 * Displays the client's cryptographic identity (signer ID and public key)
 * for worker authorization, along with a pre-formatted CLI command for
 * easy worker registration.
 *
 * This composable is stateless and delegates all side effects (clipboard operations,
 * notifications, error handling) to the provided callbacks.
 *
 * @param signerId The stable signer (device) ID, or null if not loaded.
 * @param publicKeyBase64 The Base64-encoded public key, or null if not loaded.
 * @param isLoading Whether the identity information is being fetched.
 * @param error Error message if retrieval failed, or null if successful.
 * @param onCopySignerId Callback when the user clicks the copy button for the signer ID.
 * @param onCopyPublicKey Callback when the user clicks the copy button for the public key.
 * @param onCopyRegisterCommand Callback when the user clicks the copy button for the CLI command.
 * @param modifier Modifier applied to the tab.
 */
@Composable
fun E2EASecurityTab(
    signerId: String?,
    publicKeyBase64: String?,
    isLoading: Boolean,
    error: String?,
    onCopySignerId: () -> Unit,
    onCopyPublicKey: () -> Unit,
    onCopyRegisterCommand: () -> Unit,
    modifier: Modifier = Modifier
) {

    SettingsListPageTemplate(
        title = "E2EA Security",
        subtitle = "Worker authorization credentials for end-to-end request signing",
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Display error if present
            if (!error.isNullOrBlank()) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Error Loading Identity",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Worker Security Card
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Worker Security",
                        style = MaterialTheme.typography.titleMedium
                    )

                    // Signer ID section (display is truncated, but copy uses full value)
                    // Text selection is disabled to prevent users from accidentally copying incomplete strings
                    E2EACredentialField(
                        label = "Signer ID",
                        value = signerId ?: "Not loaded",
                        displayValue = truncateForDisplay(signerId),
                        isLoading = isLoading,
                        onCopy = onCopySignerId,
                        isSelectable = false
                    )

                    // Public Key section
                    E2EACredentialField(
                        label = "Public Key (Base64)",
                        value = publicKeyBase64 ?: "Not loaded",
                        isLoading = isLoading,
                        onCopy = onCopyPublicKey
                    )
                }
            }

            // CLI Registration Command Card
            if (!signerId.isNullOrBlank() && !publicKeyBase64.isNullOrBlank()) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Worker Registration Command",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Copy this command to your worker startup script to authorize this client:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        E2EACommandField(
                            onCopy = onCopyRegisterCommand
                        )
                    }
                }
            }
        }
    }
}

/**
 * A read-only credential field displaying an identity value with a copy button.
 *
 * @param label The field label (e.g., "Signer ID").
 * @param value The actual value to copy (passed to the onCopy callback).
 * @param isLoading Whether the value is currently being loaded.
 * @param onCopy Callback invoked when the user clicks the copy button.
 * @param displayValue Optional display-friendly version of the value (e.g., truncated).
 *                      If null, the full `value` is displayed.
 * @param isSelectable Whether the text field allows manual selection. Default is true.
 *                      Disable this for truncated/masked values to prevent users from accidentally
 *                      copying incomplete strings. Use the Copy button instead.
 */
@Composable
private fun E2EACredentialField(
    label: String,
    value: String,
    isLoading: Boolean,
    onCopy: () -> Unit,
    displayValue: String? = null,
    isSelectable: Boolean = true
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectable) {
                SelectionContainer(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        displayValue ?: value,
                        modifier = Modifier
                            .padding(8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    displayValue ?: value,
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCopy, enabled = !isLoading) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy $label")
            }
        }
    }
}

/**
 * A read-only command field for displaying the CLI registration command template.
 *
 * This field displays a placeholder/template command with `<SIGNER_ID>` and `<PUBLIC_KEY>` placeholders.
 * The actual command (with real values) is constructed and copied by the ViewModel when the user
 * clicks the copy button.
 *
 * @param onCopy Callback when the user clicks the copy button. Should copy the real, functional command.
 * @param isSelectable Whether the command template text allows manual selection. Default is true.
 *                      Users may occasionally need to highlight specific flags or syntax.
 */
@Composable
private fun E2EACommandField(
    onCopy: () -> Unit,
    isSelectable: Boolean = true
) {
    val commandTemplate = "./start-worker.sh --add-trusted-signer --signer-id=<SIGNER_ID> --public-key-base64=<PUBLIC_KEY>"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectable) {
                SelectionContainer(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        commandTemplate,
                        modifier = Modifier
                            .padding(8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    commandTemplate,
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy command")
            }
        }
    }
}

/**
 * Truncates a string for display purposes, showing the first 8 characters followed by "...".
 *
 * Handles null, empty, or short strings gracefully:
 * - If the string is null or empty, returns null (allowing the caller to use the full value as fallback).
 * - If the string is shorter than 8 characters, returns the full string.
 * - Otherwise, returns the first 8 characters followed by "...".
 *
 * @param value The string to truncate, or null.
 * @return The truncated string, or null if input is null/empty.
 */
private fun truncateForDisplay(value: String?): String? {
    return when {
        value.isNullOrEmpty() -> null
        value.length <= 8 -> value
        else -> value.take(8) + "..."
    }
}
