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
import eu.torvian.chatbot.app.viewmodel.auth.AuthViewModel
import eu.torvian.chatbot.app.viewmodel.auth.LoginFormState
import org.koin.compose.viewmodel.koinViewModel

/**
 * Login screen for user authentication.
 *
 * @param onNavigateToRegister Callback to navigate to registration screen
 * @param authViewModel ViewModel for authentication operations
 */
@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit,
    authViewModel: AuthViewModel = koinViewModel()
) {
    val loginFormState by authViewModel.loginFormState.collectAsState()
    val availableAccounts by authViewModel.availableAccounts.collectAsState()
    val accountSwitchInProgress by authViewModel.accountSwitchInProgress.collectAsState()
    val scrollState = rememberScrollState()

    LoginScreenContent(
        loginFormState = loginFormState,
        availableAccounts = availableAccounts,
        accountSwitchInProgress = accountSwitchInProgress,
        scrollState = scrollState,
        onUsernameChange = { username ->
            authViewModel.updateLoginForm(username = username)
        },
        onPasswordChange = { password ->
            authViewModel.updateLoginForm(password = password)
        },
        onLogin = {
            authViewModel.login()
        },
        onNavigateToRegister = onNavigateToRegister,
        onSwitchAccount = { userId ->
            authViewModel.switchAccount(userId)
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
    onSwitchAccount: (Long) -> Unit
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
                    modifier = Modifier.padding(top = 8.dp)
                )
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
                enabled = !loginFormState.isLoading
            ) {
                Text("Sign Up")
            }
        }
    }
}
