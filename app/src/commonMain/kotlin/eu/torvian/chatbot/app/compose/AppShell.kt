package eu.torvian.chatbot.app.compose

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import eu.torvian.chatbot.app.compose.auth.AuthLoadingScreen
import eu.torvian.chatbot.app.compose.auth.ForcePasswordChangeScreen
import eu.torvian.chatbot.app.compose.snackbar.SnackbarVisualsWithError
import eu.torvian.chatbot.app.domain.events.AppError
import eu.torvian.chatbot.app.domain.events.AppSuccess
import eu.torvian.chatbot.app.domain.events.AppWarning
import eu.torvian.chatbot.app.domain.events.SnackbarInteractionEvent
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.action_retry
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.viewmodel.auth.AuthViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.koin.compose.currentKoinScope
import org.koin.compose.viewmodel.koinViewModel

/**
 * The main application shell responsible for top-level layout and authentication-aware navigation.
 *
 * This component handles:
 * - Authentication state checking and routing
 * - Conditional navigation between auth flow and main app
 * - Global error handling and snackbar display
 * - User context management
 */
@Composable
fun AppShell() {
    val authViewModel: AuthViewModel = koinViewModel()
    val authState by authViewModel.authState.collectAsState()
    val eventBus: EventBus = currentKoinScope().get()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Check initial authentication state on app startup
    LaunchedEffect(Unit) {
        authViewModel.checkInitialAuthState()
    }

    // Global error handling (preserve existing superior logic)
    LaunchedEffect(eventBus) {
        eventBus.events.collect { event ->
            if (event !is AppError && event !is AppWarning && event !is AppSuccess) {
                return@collect
            }

            // Launch a new coroutine to handle the Snackbar display
            scope.launch {
                val message: String
                val actionLabel: String?
                val duration: SnackbarDuration

                when (event) {
                    is AppError -> {
                        message = event.message
                        actionLabel = if (event.isRetryable) getString(Res.string.action_retry) else null
                        duration = if (event.isRetryable) SnackbarDuration.Indefinite else SnackbarDuration.Long
                    }

                    is AppWarning -> {
                        message = event.message
                        actionLabel = null
                        duration = SnackbarDuration.Long
                    }

                    is AppSuccess -> {
                        message = event.message
                        actionLabel = null
                        duration = SnackbarDuration.Short
                    }

                    else -> return@launch
                }

                // Show the Snackbar with custom visuals
                val visuals = SnackbarVisualsWithError(
                    isError = event is AppError,
                    message = message,
                    actionLabel = actionLabel,
                    duration = duration
                )
                val result = snackbarHostState.showSnackbar(visuals)

                // Emit a new event to communicate the Snackbar interaction back to ViewModels
                eventBus.emitEvent(
                    SnackbarInteractionEvent(
                        originalAppEventId = event.eventId,
                        isActionPerformed = result == SnackbarResult.ActionPerformed
                    )
                )
            }
        }
    }

    // Route based on authentication state
    when (val currentAuthState = authState) {
        is AuthState.Loading -> {
            AuthLoadingScreen()
        }

        is AuthState.Unauthenticated -> {
            AuthenticationFlow(
                snackbarHostState = snackbarHostState,
                authViewModel = authViewModel
            )
        }

        is AuthState.Authenticated -> {
            // Check if user needs to change password before accessing the app
            if (currentAuthState.requiresPasswordChange) {
                ForcePasswordChangeScreen(
                    authState = currentAuthState
                )
            } else {
                MainApplicationFlow(
                    authState = currentAuthState,
                    snackbarHostState = snackbarHostState,
                    authViewModel = authViewModel
                )
            }
        }
    }
}
