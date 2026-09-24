package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import eu.torvian.chatbot.app.viewmodel.auth.AuthEntryViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Authentication flow navigation for unauthenticated users.
 *
 * The register destination is guarded: when the server disallows public self-registration,
 * navigation to it redirects back to the login screen.
 */
@Composable
fun AuthenticationFlow(
    snackbarHostState: SnackbarHostState,
) {
    val authEntryViewModel: AuthEntryViewModel = koinViewModel()
    val selfRegistrationEnabled by authEntryViewModel.selfRegistrationEnabled.collectAsState()
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
                        authEntryViewModel = authEntryViewModel
                    )
                }

                composable<Register> {
                    // Guard: stale or deep navigation must not reach registration while it is disabled.
                    if (selfRegistrationEnabled) {
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
                            authEntryViewModel = authEntryViewModel
                        )
                    } else {
                        LaunchedEffect(Unit) {
                            navController.navigate(Login) {
                                popUpTo(Register) { inclusive = true }
                            }
                        }
                    }
                }
            }
        }
    }
}