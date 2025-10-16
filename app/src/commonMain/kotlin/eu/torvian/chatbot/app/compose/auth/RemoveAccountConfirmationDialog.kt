package eu.torvian.chatbot.app.compose.auth

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.auth.AccountData

/**
 * Confirmation dialog before removing an account.
 *
 * Shows a warning message about removing account data and whether
 * the user will be logged out if removing the active account.
 */
@Composable
fun RemoveAccountConfirmationDialog(
    account: AccountData,
    currentAuthState: AuthState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val isCurrentAccount = currentAuthState is AuthState.Authenticated &&
                          currentAuthState.userId == account.user.id

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove Account") },
        text = {
            Text(
                if (isCurrentAccount) {
                    "Are you sure you want to remove the account '${account.user.username}'?\n\n" +
                    "This is your currently active account. Removing it will log you out and " +
                    "delete all stored authentication data for this account."
                } else {
                    "Are you sure you want to remove the account '${account.user.username}'?\n\n" +
                    "This will permanently delete all stored authentication data for this account."
                }
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

