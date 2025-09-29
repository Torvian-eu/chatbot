package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import eu.torvian.chatbot.app.compose.auth.LoginScreen
import eu.torvian.chatbot.app.compose.auth.RegisterScreen
import eu.torvian.chatbot.app.compose.snackbar.SharedSnackbar
import eu.torvian.chatbot.app.compose.snackbar.SnackbarVisualsWithError
import eu.torvian.chatbot.app.domain.navigation.Login
import eu.torvian.chatbot.app.domain.navigation.Register
import eu.torvian.chatbot.app.viewmodel.auth.AuthViewModel

/**
 * Authentication flow navigation for unauthenticated users.
 */
@Composable
fun AuthenticationFlow(
    snackbarHostState: SnackbarHostState,
    authViewModel: AuthViewModel
) {
    val navController = rememberNavController()

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                val visualsWithError = data.visuals as? SnackbarVisualsWithError
                SharedSnackbar(data, visualsWithError)
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            NavHost(
                navController = navController,
                startDestination = Login
            ) {
                composable<Login> {
                    LoginScreen(
                        onNavigateToRegister = {
                            navController.navigate(Register) {
                                // Don't add to back stack to prevent back navigation to login
                                popUpTo(Login) { inclusive = true }
                            }
                        },
                        authViewModel = authViewModel
                    )
                }

                composable<Register> {
                    RegisterScreen(
                        onNavigateToLogin = {
                            navController.navigate(Login) {
                                popUpTo(Register) { inclusive = true }
                            }
                        },
                        onRegistrationSuccess = {
                            navController.navigate(Login) {
                                popUpTo(Register) { inclusive = true }
                            }
                        },
                        authViewModel = authViewModel
                    )
                }
            }
        }
    }
}