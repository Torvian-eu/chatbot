package eu.torvian.chatbot.app.compose.auth

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
import eu.torvian.chatbot.app.viewmodel.auth.AuthViewModel
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
    val scrollState = rememberScrollState()

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
                    onValueChange = { username ->
                        authViewModel.updateLoginForm(
                            username = username,
                            password = loginFormState.password
                        )
                    },
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
                    onValueChange = { password ->
                        authViewModel.updateLoginForm(
                            username = loginFormState.username,
                            password = password
                        )
                    },
                    label = "Password",
                    isError = loginFormState.passwordError != null,
                    errorMessage = loginFormState.passwordError,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        if (loginFormState.isValid) {
                            authViewModel.login(
                                loginFormState.username,
                                loginFormState.password
                            )
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
                    onClick = {
                        authViewModel.login(
                            loginFormState.username,
                            loginFormState.password
                        )
                    },
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
