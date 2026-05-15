package eu.torvian.chatbot.app.compose.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Dialog that informs the user about their restricted session status.
 *
 * This dialog is shown when a user logs in from an unrecognized device in WARNING mode,
 * explaining why certain features are limited and how to lift the restrictions.
 *
 * @param isRequestingVerification Whether a device verification email request is in progress.
 * @param verificationEmailSent Whether a verification email has been sent.
 * @param verificationError Error message for verification operations, if any.
 * @param onRequestVerificationEmail Called when the user clicks the "Verify via Email" button.
 * @param onCheckVerificationStatus Called when the user clicks the "Check Status" button.
 * @param onDismiss Called when the user clicks the "Close" button
 * @param modifier Optional modifier for the dialog
 */
@Composable
fun RestrictedSessionInfoDialog(
    isRequestingVerification: Boolean,
    verificationEmailSent: Boolean,
    verificationError: String?,
    onRequestVerificationEmail: () -> Unit,
    onCheckVerificationStatus: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Account Security: Restricted Session")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RestrictedSessionWarning(
                    message = "You have logged in from an unrecognized device. For your security, " +
                            "this session has restricted permissions. You cannot change your password " +
                            "or manage other sessions from here. To lift these restrictions, " +
                            "please approve this login from an existing trusted device."
                )

                if (verificationEmailSent) {
                    SuccessMessage(
                        message = "Verification email sent to your registered address. " +
                                "Please check your inbox (and spam folder)."
                    )
                }

                verificationError?.let { error ->
                    ErrorMessage(message = error)
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!verificationEmailSent) {
                    OutlinedButton(
                        onClick = onRequestVerificationEmail,
                        enabled = !isRequestingVerification
                    ) {
                        Text("Verify via Email")
                    }
                }
                Button(
                    onClick = onCheckVerificationStatus,
                    enabled = !isRequestingVerification
                ) {
                    Text("Check Status")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        modifier = modifier
    )
}
