package eu.torvian.chatbot.app.compose.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.torvian.chatbot.app.viewmodel.auth.LoginFormState

/**
 * Dialog for adding a new account without logging out from the current one.
 *
 * Shows a login form within a dialog, allowing users to authenticate
 * with additional accounts while remaining logged in to their current account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountDialog(
    loginFormState: LoginFormState,
    onDismiss: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = !loginFormState.isLoading,
            dismissOnClickOutside = !loginFormState.isLoading
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Header with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Add Account",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    IconButton(
                        onClick = onDismiss,
                        enabled = !loginFormState.isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close"
                        )
                    }
                }

                Text(
                    text = "Sign in with another account",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                )

                // Login Form
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Username Field
                    AuthTextField(
                        value = loginFormState.username,
                        onValueChange = onUsernameChange,
                        label = "Username or Email",
                        isError = loginFormState.usernameError != null,
                        errorMessage = loginFormState.usernameError,
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        enabled = !loginFormState.isLoading
                    )

                    // Password Field
                    PasswordTextField(
                        value = loginFormState.password,
                        onValueChange = onPasswordChange,
                        label = "Password",
                        isError = loginFormState.passwordError != null,
                        errorMessage = loginFormState.passwordError,
                        imeAction = ImeAction.Done,
                        onImeAction = {
                            if (loginFormState.isValid) {
                                onLogin()
                            }
                        },
                        enabled = !loginFormState.isLoading
                    )

                    // General Error Message
                    loginFormState.generalError?.let { error ->
                        ErrorMessage(
                            message = error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    // Login Button
                    AuthButton(
                        onClick = onLogin,
                        text = "Sign In",
                        isLoading = loginFormState.isLoading,
                        enabled = loginFormState.isValid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

