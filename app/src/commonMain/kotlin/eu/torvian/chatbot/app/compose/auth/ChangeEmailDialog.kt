package eu.torvian.chatbot.app.compose.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
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
import eu.torvian.chatbot.app.viewmodel.auth.ChangeEmailFormState

/**
 * Dialog for changing the authenticated user's email address.
 *
 * Allows the user to enter their current password and set a new email.
 * The dialog is disabled when the session is restricted (untrusted device).
 *
 * @param formState The current state of the change email form
 * @param isRestricted Whether the current session is restricted (untrusted device)
 * @param onDismiss Called when the dialog should be closed
 * @param onCurrentPasswordChange Called when the current password field changes
 * @param onNewEmailChange Called when the new email field changes
 * @param onChangeEmail Called when the user requests to change their email
 */
@Composable
fun ChangeEmailDialog(
    formState: ChangeEmailFormState,
    isRestricted: Boolean,
    onDismiss: () -> Unit,
    onCurrentPasswordChange: (String) -> Unit,
    onNewEmailChange: (String) -> Unit,
    onChangeEmail: () -> Unit
) {
    var showCurrentPassword by remember { mutableStateOf(false) }

    // Close dialog on successful email change
    LaunchedEffect(formState.emailChangeSuccessEvent) {
        if (formState.emailChangeSuccessEvent) {
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Email") },
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
                            text = "Cannot Change Email",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Email cannot be changed from an untrusted device. Please verify your identity from a trusted device first.",
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

                    // New email field
                    OutlinedTextField(
                        value = formState.newEmail,
                        onValueChange = onNewEmailChange,
                        label = { Text("New Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { onChangeEmail() }
                        ),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = null
                            )
                        },
                        isError = formState.newEmailError != null,
                        supportingText = formState.newEmailError?.let {
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
                }
            }
        },
        confirmButton = {
            if (!isRestricted) {
                Button(
                    onClick = onChangeEmail,
                    enabled = !formState.isLoading
                ) {
                    Text(if (formState.isLoading) "Changing..." else "Change Email")
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
