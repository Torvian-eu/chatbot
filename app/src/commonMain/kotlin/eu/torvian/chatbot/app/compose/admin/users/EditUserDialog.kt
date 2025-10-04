package eu.torvian.chatbot.app.compose.admin.users

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.viewmodel.admin.UserFormState
import eu.torvian.chatbot.common.models.UserWithDetails

/**
 * Dialog for editing a user's basic information (username and email).
 *
 * Displays a form with text fields for username and email, with validation
 * error messages. The submit button is disabled when the form is invalid or
 * loading, and shows a loading indicator during submission.
 *
 * @param user The user being edited
 * @param formState The current state of the edit form including field values and validation errors
 * @param onDismiss Callback invoked when the dialog is dismissed
 * @param onConfirm Callback invoked when the submit button is clicked
 * @param onUsernameChange Callback invoked when the username field changes
 * @param onEmailChange Callback invoked when the email field changes
 */
@Composable
fun EditUserDialog(
    user: UserWithDetails,
    formState: UserFormState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit User") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Username field
                OutlinedTextField(
                    value = formState.username,
                    onValueChange = onUsernameChange,
                    label = { Text("Username") },
                    isError = formState.usernameError != null,
                    supportingText = formState.usernameError?.let { { Text(it) } },
                    enabled = !formState.isLoading,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Email field
                OutlinedTextField(
                    value = formState.email ?: "",
                    onValueChange = onEmailChange,
                    label = { Text("Email (optional)") },
                    isError = formState.emailError != null,
                    supportingText = formState.emailError?.let { { Text(it) } },
                    enabled = !formState.isLoading,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // General error
                if (formState.generalError != null) {
                    Text(
                        text = formState.generalError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = formState.isValid && !formState.isLoading
            ) {
                if (formState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !formState.isLoading) {
                Text("Cancel")
            }
        }
    )
}

