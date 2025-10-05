package eu.torvian.chatbot.app.compose.auth

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.auth.AuthViewModel
import eu.torvian.chatbot.app.viewmodel.auth.PasswordChangeFormState
import org.koin.compose.viewmodel.koinViewModel

/**
 * Force password change screen that is shown when a user must change their password
 * before being allowed to access the application.
 *
 * This screen cannot be dismissed or navigated away from - the user must either
 * change their password successfully or log out.
 *
 * @param authState The current authenticated state
 * @param authViewModel ViewModel for authentication operations
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForcePasswordChangeScreen(
    authState: AuthState.Authenticated,
    authViewModel: AuthViewModel = koinViewModel()
) {
    val passwordChangeFormState by authViewModel.passwordChangeFormState.collectAsState()
    val scrollState = rememberScrollState()

    ForcePasswordChangeScreenContent(
        username = authState.username,
        passwordChangeFormState = passwordChangeFormState,
        scrollState = scrollState,
        onNewPasswordChange = { newPassword ->
            authViewModel.updatePasswordChangeForm(newPassword = newPassword)
        },
        onConfirmPasswordChange = { confirmPassword ->
            authViewModel.updatePasswordChangeForm(confirmPassword = confirmPassword)
        },
        onChangePassword = {
            authViewModel.changePassword(authState.userId)
        },
        onLogout = authViewModel::logout,
        onAcknowledgeSuccessAndLogout = {
            authViewModel.clearPasswordChangeForm()
            authViewModel.logout()
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForcePasswordChangeScreenContent(
    username: String,
    passwordChangeFormState: PasswordChangeFormState,
    scrollState: ScrollState,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onChangePassword: () -> Unit,
    onLogout: () -> Unit,
    onAcknowledgeSuccessAndLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Password Change Required") },
                actions = {
                    if (!passwordChangeFormState.passwordChangeSuccessEvent) {
                        IconButton(onClick = onLogout) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Logout"
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // --- Conditional Content based on password change state ---
            if (passwordChangeFormState.passwordChangeSuccessEvent) {
                PasswordChangeSuccessContent(
                    onLogoutAndReturn = onAcknowledgeSuccessAndLogout
                )
            } else {
                PasswordChangeFormContent(
                    username = username,
                    passwordChangeFormState = passwordChangeFormState,
                    onNewPasswordChange = onNewPasswordChange,
                    onConfirmPasswordChange = onConfirmPasswordChange,
                    onChangePassword = onChangePassword,
                    onLogout = onLogout
                )
            }
        }
    }
}

@Composable
private fun PasswordChangeFormContent(
    username: String,
    passwordChangeFormState: PasswordChangeFormState,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onChangePassword: () -> Unit,
    onLogout: () -> Unit
) {
    // Warning Icon
    Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.error
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Password Change Required",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "For security reasons, you must change your password before continuing.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    // Password Change Form
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Change Password for: $username",
                style = MaterialTheme.typography.titleMedium
            )

            // New Password Field
            PasswordTextField(
                value = passwordChangeFormState.newPassword,
                onValueChange = onNewPasswordChange,
                label = "New Password",
                isError = passwordChangeFormState.newPasswordError != null,
                errorMessage = passwordChangeFormState.newPasswordError,
                imeAction = ImeAction.Next,
                enabled = !passwordChangeFormState.isLoading
            )

            // Confirm Password Field
            PasswordTextField(
                value = passwordChangeFormState.confirmPassword,
                onValueChange = onConfirmPasswordChange,
                label = "Confirm New Password",
                isError = passwordChangeFormState.confirmPasswordError != null,
                errorMessage = passwordChangeFormState.confirmPasswordError,
                imeAction = ImeAction.Done,
                onImeAction = onChangePassword,
                enabled = !passwordChangeFormState.isLoading
            )

            // General Error Message
            if (passwordChangeFormState.generalError != null) {
                Text(
                    text = passwordChangeFormState.generalError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Password Requirements
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Password Requirements:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• At least 8 characters",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Include uppercase and lowercase letters",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Include at least one number",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Include at least one special character",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Change Password Button
            Button(
                onClick = onChangePassword,
                modifier = Modifier.fillMaxWidth(),
                enabled = !passwordChangeFormState.isLoading && passwordChangeFormState.isValid
            ) {
                if (passwordChangeFormState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Change Password")
                }
            }

            // Logout Button
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                enabled = !passwordChangeFormState.isLoading
            ) {
                Text("Logout Instead")
            }
        }
    }
}

@Composable
private fun PasswordChangeSuccessContent(
    onLogoutAndReturn: () -> Unit
) {
    // Success Icon
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Password Changed Successfully",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Your password has been updated. Please log out and log in again with your new password.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    // Success Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "You will now be logged out.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = onLogoutAndReturn,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Logout")
            }
        }
    }
}
