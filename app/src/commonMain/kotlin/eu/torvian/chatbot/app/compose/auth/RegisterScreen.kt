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
 * Registration screen for new user signup.
 *
 * @param onNavigateToLogin Callback to navigate to login screen
 * @param onRegistrationSuccess Callback when registration is successful
 * @param authViewModel ViewModel for authentication operations
 */
@Composable
fun RegisterScreen(
    onNavigateToLogin: () -> Unit,
    onRegistrationSuccess: () -> Unit,
    authViewModel: AuthViewModel = koinViewModel()
) {
    val registerFormState by authViewModel.registerFormState.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Title - Dynamic based on registration state
        Text(
            text = if (registerFormState.registrationSuccessEvent) "Registration Complete" else "Create Account",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = if (registerFormState.registrationSuccessEvent) "Your account has been successfully created." else "Sign up to get started",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // --- Conditional Content ---
        if (registerFormState.registrationSuccessEvent) {
            RegistrationSuccessContent(
                onGoToLogin = {
                    authViewModel.acknowledgeRegistrationSuccess() // Reset state/form
                    onNavigateToLogin() // Navigate
                }
            )
        } else {
            // Registration Form
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
                        value = registerFormState.username,
                        onValueChange = { username ->
                            authViewModel.updateRegisterForm(
                                username = username,
                                email = registerFormState.email,
                                password = registerFormState.password,
                                confirmPassword = registerFormState.confirmPassword
                            )
                        },
                        label = "Username",
                        isError = registerFormState.usernameError != null,
                        errorMessage = registerFormState.usernameError,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                        enabled = !registerFormState.isLoading
                    )

                    // Email Field (Optional)
                    AuthTextField(
                        value = registerFormState.email,
                        onValueChange = { email ->
                            authViewModel.updateRegisterForm(
                                username = registerFormState.username,
                                email = email,
                                password = registerFormState.password,
                                confirmPassword = registerFormState.confirmPassword
                            )
                        },
                        label = "Email (Optional)",
                        isError = registerFormState.emailError != null,
                        errorMessage = registerFormState.emailError,
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        enabled = !registerFormState.isLoading
                    )

                    // Password Field
                    PasswordTextField(
                        value = registerFormState.password,
                        onValueChange = { password ->
                            authViewModel.updateRegisterForm(
                                username = registerFormState.username,
                                email = registerFormState.email,
                                password = password,
                                confirmPassword = registerFormState.confirmPassword
                            )
                        },
                        label = "Password",
                        isError = registerFormState.passwordError != null,
                        errorMessage = registerFormState.passwordError,
                        imeAction = ImeAction.Next,
                        enabled = !registerFormState.isLoading
                    )

                    // Confirm Password Field
                    PasswordTextField(
                        value = registerFormState.confirmPassword,
                        onValueChange = { confirmPassword ->
                            authViewModel.updateRegisterForm(
                                username = registerFormState.username,
                                email = registerFormState.email,
                                password = registerFormState.password,
                                confirmPassword = confirmPassword
                            )
                        },
                        label = "Confirm Password",
                        isError = registerFormState.confirmPasswordError != null,
                        errorMessage = registerFormState.confirmPasswordError,
                        imeAction = ImeAction.Done,
                        onImeAction = {
                            if (registerFormState.isValid) {
                                authViewModel.register(
                                    registerFormState.username,
                                    registerFormState.email,
                                    registerFormState.password,
                                    registerFormState.confirmPassword
                                )
                            }
                        },
                        enabled = !registerFormState.isLoading
                    )

                    // General Error Message
                    registerFormState.generalError?.let { error ->
                        ErrorMessage(
                            message = error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    // Register Button
                    AuthButton(
                        onClick = {
                            authViewModel.register(
                                registerFormState.username,
                                registerFormState.email,
                                registerFormState.password,
                                registerFormState.confirmPassword
                            )
                        },
                        text = "Create Account",
                        isLoading = registerFormState.isLoading,
                        enabled = registerFormState.isValid,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // Login Link
            Row(
                modifier = Modifier.padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Already have an account? ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onNavigateToLogin,
                    enabled = !registerFormState.isLoading
                ) {
                    Text("Sign In")
                }
            }
        }
    }
}

/**
 * Content displayed after successful registration.
 */
@Composable
private fun RegistrationSuccessContent(
    onGoToLogin: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Thank you for joining!",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "You can now sign in using your new credentials. Click the button below to navigate to the login screen.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            AuthButton(
                onClick = onGoToLogin,
                text = "Go to Sign In",
                modifier = Modifier.padding(top = 8.dp),
                enabled = true,
                isLoading = false
            )
        }
    }
}
