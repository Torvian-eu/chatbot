package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.VersionInfo
import eu.torvian.chatbot.app.compose.auth.AuthLoadingScreen
import eu.torvian.chatbot.app.compose.auth.ForcePasswordChangeScreen
import eu.torvian.chatbot.app.compose.dialogs.CertificateWarningDialog
import eu.torvian.chatbot.app.compose.snackbar.SnackbarVisualsWithError
import eu.torvian.chatbot.app.domain.events.AppError
import eu.torvian.chatbot.app.domain.events.AppSuccess
import eu.torvian.chatbot.app.domain.events.AppWarning
import eu.torvian.chatbot.app.domain.events.SnackbarInteractionEvent
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.action_retry
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.service.security.CertificateTrustService
import eu.torvian.chatbot.app.viewmodel.AppViewModel
import eu.torvian.chatbot.app.viewmodel.auth.SessionViewModel
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
 * - Certificate trust decisions via dialog
 */
@Composable
fun AppShell() {
    val sessionViewModel: SessionViewModel = koinViewModel()
    val appViewModel: AppViewModel = koinViewModel()
    val authState by sessionViewModel.authState.collectAsState()
    val isVersionMismatch by appViewModel.isVersionMismatch.collectAsState()
    val serverInfo by appViewModel.serverInfo.collectAsState()
    val eventBus: EventBus = currentKoinScope().get()

    var isVersionWarningDismissed by rememberSaveable { mutableStateOf(false) }

    // Get the CertificateTrustService from Koin
    val certificateTrustService: CertificateTrustService = currentKoinScope().get()

    // Collect the dialog state from the service
    val certificateDetails by certificateTrustService.trustRequestState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Check initial authentication state on app startup
    LaunchedEffect(Unit) {
        sessionViewModel.checkInitialAuthState()
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

    Column(modifier = Modifier.fillMaxSize()) {
        if (isVersionMismatch && !isVersionWarningDismissed) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Version Mismatch Detected",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "The application version does not match the server version. This may cause unexpected behavior.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "App Version: ${VersionInfo.VERSION} | Server Version: ${serverInfo?.version ?: "Unknown"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    IconButton(onClick = { isVersionWarningDismissed = true }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss version mismatch warning"
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            // Route based on authentication state
            when (val currentAuthState = authState) {
                is AuthState.Loading -> {
                    AuthLoadingScreen()
                }

                is AuthState.Unauthenticated -> {
                    AuthenticationFlow(
                        snackbarHostState = snackbarHostState
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
                            snackbarHostState = snackbarHostState
                        )
                    }
                }
            }
        }
    }

    // Display the CertificateWarningDialog when a trust request is active.
    // This ensures it can be displayed at any time, overlaying the entire application.
    certificateDetails?.let { details ->
        CertificateWarningDialog(
            details = details,
            onAccept = { certificateTrustService.onUserResponse(true) },
            onReject = { certificateTrustService.onUserResponse(false) }
        )
    }
}
