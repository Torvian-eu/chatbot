package eu.torvian.chatbot.app.compose.admin.users

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.viewmodel.admin.PasswordFormState
import eu.torvian.chatbot.common.models.UserWithDetails

/**
 * Dialog for an administrator to change a user's password.
 *
 * Provides fields for entering and confirming a new password, with validation
 * and a toggle to show/hide the password. The submit button is disabled when
 * the form is invalid or loading.
 *
 * @param user The user whose password is being changed
 * @param formState The current state of the password form including field values and validation errors
 * @param onDismiss Callback invoked when the dialog is dismissed
 * @param onConfirm Callback invoked when the change password button is clicked
 * @param onNewPasswordChange Callback invoked when the new password field changes
 * @param onConfirmPasswordChange Callback invoked when the confirm password field changes
 */
@Composable
fun ChangePasswordDialog(
    user: UserWithDetails,
    formState: PasswordFormState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Password for ${user.username}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // New password field
                OutlinedTextField(
                    value = formState.newPassword,
                    onValueChange = onNewPasswordChange,
                    label = { Text("New Password") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    isError = formState.newPasswordError != null,
                    supportingText = formState.newPasswordError?.let { { Text(it) } },
                    enabled = !formState.isLoading,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    }
                )

                // Confirm password field
                OutlinedTextField(
                    value = formState.confirmPassword,
                    onValueChange = onConfirmPasswordChange,
                    label = { Text("Confirm Password") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    isError = formState.confirmPasswordError != null,
                    supportingText = formState.confirmPasswordError?.let { { Text(it) } },
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
                    Text("Change Password")
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

