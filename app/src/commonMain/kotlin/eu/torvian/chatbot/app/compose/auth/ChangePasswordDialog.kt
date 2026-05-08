package eu.torvian.chatbot.app.compose.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.viewmodel.auth.PasswordChangeFormState

/**
 * Dialog for changing the authenticated user's password.
 *
 * Allows the user to enter their current password and set a new one.
 * The dialog is disabled when the session is restricted (untrusted device).
 *
 * @param formState The current state of the password change form
 * @param isRestricted Whether the current session is restricted (untrusted device)
 * @param onDismiss Called when the dialog should be closed
 * @param onCurrentPasswordChange Called when the current password field changes
 * @param onNewPasswordChange Called when the new password field changes
 * @param onConfirmPasswordChange Called when the confirm password field changes
 * @param onChangePassword Called when the user requests to change their password
 */
@Composable
fun ChangePasswordDialog(
    formState: PasswordChangeFormState,
    isRestricted: Boolean,
    onDismiss: () -> Unit,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onChangePassword: () -> Unit
) {
    var showCurrentPassword by remember { mutableStateOf(false) }
    var showNewPassword by remember { mutableStateOf(false) }

    // Close dialog on successful password change
    LaunchedEffect(formState.passwordChangeSuccessEvent) {
        if (formState.passwordChangeSuccessEvent) {
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Password") },
        text = {
            if (isRestricted) {
                // Show restricted message instead of form
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Cannot Change Password",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Password cannot be changed from an untrusted device. Please verify your identity from a trusted device first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Current password field
                    OutlinedTextField(
                        value = formState.currentPassword,
                        onValueChange = onCurrentPasswordChange,
                        label = { Text("Current Password") },
                        singleLine = true,
                        visualTransformation = if (showCurrentPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { /* Move to next field */ }
                        ),
                        trailingIcon = {
                            IconButton(onClick = { showCurrentPassword = !showCurrentPassword }) {
                                Icon(
                                    imageVector = if (showCurrentPassword) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (showCurrentPassword) {
                                        "Hide current password"
                                    } else {
                                        "Show current password"
                                    }
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null
                            )
                        },
                        isError = formState.currentPasswordError != null,
                        supportingText = formState.currentPasswordError?.let {
                            { Text(it, color = MaterialTheme.colorScheme.error) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // New password field
                    OutlinedTextField(
                        value = formState.newPassword,
                        onValueChange = onNewPasswordChange,
                        label = { Text("New Password") },
                        singleLine = true,
                        visualTransformation = if (showNewPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { /* Move to next field */ }
                        ),
                        trailingIcon = {
                            IconButton(onClick = { showNewPassword = !showNewPassword }) {
                                Icon(
                                    imageVector = if (showNewPassword) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (showNewPassword) {
                                        "Hide new password"
                                    } else {
                                        "Show new password"
                                    }
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null
                            )
                        },
                        isError = formState.newPasswordError != null,
                        supportingText = formState.newPasswordError?.let {
                            { Text(it, color = MaterialTheme.colorScheme.error) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Confirm new password field
                    OutlinedTextField(
                        value = formState.confirmPassword,
                        onValueChange = onConfirmPasswordChange,
                        label = { Text("Confirm New Password") },
                        singleLine = true,
                        visualTransformation = if (showNewPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { onChangePassword() }
                        ),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null
                            )
                        },
                        isError = formState.confirmPasswordError != null,
                        supportingText = formState.confirmPasswordError?.let {
                            { Text(it, color = MaterialTheme.colorScheme.error) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // General error message
                    formState.generalError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Password requirements hint
                    PasswordRequirementsHint()
                }
            }
        },
        confirmButton = {
            if (!isRestricted) {
                Button(
                    onClick = onChangePassword,
                    enabled = !formState.isLoading
                ) {
                    Text(if (formState.isLoading) "Changing..." else "Change Password")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
