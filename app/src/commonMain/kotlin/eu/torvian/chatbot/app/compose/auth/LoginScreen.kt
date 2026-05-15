package eu.torvian.chatbot.app.compose.auth

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.app.viewmodel.auth.AccountManagementViewModel
import eu.torvian.chatbot.app.viewmodel.auth.AuthEntryViewModel
import eu.torvian.chatbot.app.viewmodel.auth.LoginFormState
import org.koin.compose.viewmodel.koinViewModel

/**
 * Login screen for user authentication.
 *
 * @param onNavigateToRegister Callback to navigate to registration screen
 * @param authEntryViewModel ViewModel for authentication entry operations
 */
@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit,
    authEntryViewModel: AuthEntryViewModel = koinViewModel(),
    accountManagementViewModel: AccountManagementViewModel = koinViewModel()
) {
    val loginFormState by authEntryViewModel.loginFormState.collectAsState()
    val availableAccounts by accountManagementViewModel.availableAccounts.collectAsState()
    val accountSwitchInProgress by accountManagementViewModel.accountSwitchInProgress.collectAsState()
    val scrollState = rememberScrollState()

    LoginScreenContent(
        loginFormState = loginFormState,
        availableAccounts = availableAccounts,
        accountSwitchInProgress = accountSwitchInProgress,
        scrollState = scrollState,
        onUsernameChange = { username ->
            authEntryViewModel.updateLoginForm(username = username)
        },
        onPasswordChange = { password ->
            authEntryViewModel.updateLoginForm(password = password)
        },
        onLogin = {
            authEntryViewModel.login()
        },
        onNavigateToRegister = onNavigateToRegister,
        onSwitchAccount = { userId ->
            accountManagementViewModel.switchAccount(userId)
        },
        onRequestVerification = {
            authEntryViewModel.requestPublicVerification()
        }
    )
}

@Composable
fun LoginScreenContent(
    loginFormState: LoginFormState,
    availableAccounts: List<AccountData>,
    accountSwitchInProgress: Boolean,
    scrollState: ScrollState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onSwitchAccount: (Long) -> Unit,
    onRequestVerification: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Title/Logo
        Text(
            text = "Welcome Back",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Sign in to your account",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Available Accounts Section (if any exist)
        if (availableAccounts.isNotEmpty()) {
            AvailableAccountsSection(
                accounts = availableAccounts,
                accountSwitchInProgress = accountSwitchInProgress,
                onSwitchAccount = onSwitchAccount,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Divider with "OR" text
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = "OR",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
        }

        // Login Form
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Username Field
                AuthTextField(
                    value = loginFormState.username,
                    onValueChange = onUsernameChange,
                    label = "Username",
                    isError = loginFormState.usernameError != null,
                    errorMessage = loginFormState.usernameError,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                    enabled = !loginFormState.isLoading && !loginFormState.isVerifying
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
                    enabled = !loginFormState.isLoading && !loginFormState.isVerifying
                )

                // General Error Message
                loginFormState.generalError?.let { error ->
                    ErrorMessage(
                        message = error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // Verification Results (Success or Error) - shown outside the trigger block
                loginFormState.verificationMessage?.let { message ->
                    if (loginFormState.isVerificationSuccess) {
                        SuccessMessage(
                            message = message,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        ErrorMessage(
                            message = message,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }

                // Verification Trigger - shown when VERIFICATION_REQUIRED error occurs
                if (loginFormState.showVerificationTrigger && !loginFormState.isVerificationSuccess) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "This device is not recognized. You can request a verification email to verify it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Buttons side-by-side: Request Verification and Sign In
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AuthButton(
                                onClick = onRequestVerification,
                                text = "Verify via Email",
                                isLoading = loginFormState.isVerifying,
                                enabled = !loginFormState.isVerifying,
                                isPrimary = false,
                                modifier = Modifier.weight(1f)
                            )
                            AuthButton(
                                onClick = onLogin,
                                text = "Sign In",
                                isLoading = loginFormState.isLoading,
                                enabled = loginFormState.isValid && !loginFormState.isVerifying,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    // Login Button (when no verification needed)
                    AuthButton(
                        onClick = onLogin,
                        text = "Sign In",
                        isLoading = loginFormState.isLoading,
                        enabled = loginFormState.isValid && !loginFormState.isVerifying,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Register Link
        Row(
            modifier = Modifier.padding(top = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Don't have an account? ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onNavigateToRegister,
                enabled = !loginFormState.isLoading && !loginFormState.isVerifying
            ) {
                Text("Sign Up")
            }
        }
    }
}
