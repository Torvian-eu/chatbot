package eu.torvian.chatbot.app.compose.auth

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Dialog that informs the user about their restricted session status.
 *
 * This dialog is shown when a user logs in from an unrecognized device in WARNING mode,
 * explaining why certain features are limited and how to lift the restrictions.
 *
 * @param onDismiss Called when the user clicks the "I Understand" button
 * @param modifier Optional modifier for the dialog
 */
@Composable
fun RestrictedSessionInfoDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Restricted Session")
        },
        text = {
            RestrictedSessionWarning(
                message = "You have logged in from an unrecognized device. For your security, " +
                    "this session has restricted permissions. You cannot change your password " +
                    "or manage other sessions from here. To lift these restrictions, " +
                    "please approve this login from an existing trusted device."
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("I Understand")
            }
        },
        modifier = modifier
    )
}
