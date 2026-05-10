package eu.torvian.chatbot.app.compose.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.viewmodel.auth.AuthViewModel

/**
 * Dialog that informs the user about their restricted session status.
 *
 * This dialog is shown when a user logs in from an unrecognized device in WARNING mode,
 * explaining why certain features are limited and how to lift the restrictions.
 *
 * @param viewModel The auth view model for handling verification operations
 * @param onDismiss Called when the user clicks the "Close" button
 * @param modifier Optional modifier for the dialog
 */
@Composable
fun RestrictedSessionInfoDialog(
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRequestingVerification by viewModel.isRequestingVerification.collectAsState()
    val verificationEmailSent by viewModel.verificationEmailSent.collectAsState()
    val verificationError by viewModel.verificationError.collectAsState()

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
                        onClick = { viewModel.requestVerificationEmail() },
                        enabled = !isRequestingVerification
                    ) {
                        Text("Verify via Email")
                    }
                }
                Button(
                    onClick = { viewModel.checkVerificationStatus() },
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
