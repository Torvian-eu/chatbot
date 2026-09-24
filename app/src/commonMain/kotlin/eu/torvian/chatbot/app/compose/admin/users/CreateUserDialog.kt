package eu.torvian.chatbot.app.compose.admin.users

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.auth.PasswordRequirementsHint
import eu.torvian.chatbot.app.compose.auth.UsernameRequirementsHint
import eu.torvian.chatbot.app.viewmodel.admin.CreateUserFormState
import eu.torvian.chatbot.common.security.PasswordValidationConfig
import eu.torvian.chatbot.common.security.UsernameValidationConfig

/**
 * Dialog for an administrator to create a new user account.
 *
 * Collects username, optional email, and an administrator-chosen initial password, plus a
 * "require password change on first login" option that defaults to ON. Validation failures
 * surface as inline messages under the offending field; the submit button is disabled while the
 * form is invalid or loading. Requirement hints under the username and password fields reflect
 * the active account validation policy.
 *
 * @param formState The current state of the create form including field values and validation errors
 * @param passwordValidationConfig Password rules used to render the password requirement hint
 * @param usernameValidationConfig Username rules used to render the username requirement hint
 * @param onDismiss Callback invoked when the dialog is dismissed
 * @param onConfirm Callback invoked when the create button is clicked
 * @param onUsernameChange Callback invoked when the username field changes
 * @param onEmailChange Callback invoked when the email field changes
 * @param onPasswordChange Callback invoked when the password field changes
 * @param onConfirmPasswordChange Callback invoked when the confirm password field changes
 * @param onRequiresPasswordChangeChange Callback invoked when the password-change-required toggle changes
 */
@Composable
fun CreateUserDialog(
    formState: CreateUserFormState,
    passwordValidationConfig: PasswordValidationConfig,
    usernameValidationConfig: UsernameValidationConfig,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onRequiresPasswordChangeChange: (Boolean) -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add User") },
        text = {
            // The requirement hints add enough height to overflow small viewports, so keep the
            // form scrollable the way the registration form does.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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

                // Username requirements hint
                UsernameRequirementsHint(
                    config = usernameValidationConfig,
                    modifier = Modifier.fillMaxWidth()
                )

                // Email field
                OutlinedTextField(
                    value = formState.email,
                    onValueChange = onEmailChange,
                    label = { Text("Email (optional)") },
                    isError = formState.emailError != null,
                    supportingText = formState.emailError?.let { { Text(it) } },
                    enabled = !formState.isLoading,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Initial password field
                OutlinedTextField(
                    value = formState.password,
                    onValueChange = onPasswordChange,
                    label = { Text("Initial Password") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    isError = formState.passwordError != null,
                    supportingText = formState.passwordError?.let { { Text(it) } },
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

                // Password requirements hint
                PasswordRequirementsHint(
                    config = passwordValidationConfig,
                    modifier = Modifier.fillMaxWidth()
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

                // Require password change on first login (defaults ON)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !formState.isLoading) {
                            onRequiresPasswordChangeChange(!formState.requiresPasswordChange)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = formState.requiresPasswordChange,
                        onCheckedChange = onRequiresPasswordChangeChange,
                        enabled = !formState.isLoading
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Require password change on first login", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "User must set a new password when logging in for the first time",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

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
                    Text("Create")
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
