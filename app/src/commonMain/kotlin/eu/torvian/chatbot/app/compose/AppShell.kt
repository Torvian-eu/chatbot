package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.torvian.chatbot.app.compose.auth.AuthLoadingScreen
import eu.torvian.chatbot.app.compose.auth.LoginScreen
import eu.torvian.chatbot.app.compose.auth.RegisterScreen
import eu.torvian.chatbot.app.compose.common.OverflowTooltipText
import eu.torvian.chatbot.app.compose.settings.SettingsScreen
import eu.torvian.chatbot.app.domain.events.AppError
import eu.torvian.chatbot.app.domain.events.AppSuccess
import eu.torvian.chatbot.app.domain.events.AppWarning
import eu.torvian.chatbot.app.domain.events.SnackbarInteractionEvent
import eu.torvian.chatbot.app.domain.navigation.Chat
import eu.torvian.chatbot.app.domain.navigation.Login
import eu.torvian.chatbot.app.domain.navigation.Register
import eu.torvian.chatbot.app.domain.navigation.Settings
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.action_retry
import eu.torvian.chatbot.app.generated.resources.app_name
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.viewmodel.SessionListViewModel
import eu.torvian.chatbot.app.viewmodel.auth.AuthViewModel
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
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
@OptIn(ExperimentalMaterial3Api::class)
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
            MainApplicationFlow(
                authState = currentAuthState,
                snackbarHostState = snackbarHostState,
                authViewModel = authViewModel
            )
        }
    }
}

/**
 * Authentication flow navigation for unauthenticated users.
 */
@Composable
private fun AuthenticationFlow(
    snackbarHostState: SnackbarHostState,
    authViewModel: AuthViewModel
) {
    val navController = rememberNavController()

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                val visualsWithError = data.visuals as? SnackbarVisualsWithError
                AuthSnackbar(data, visualsWithError)
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

/**
 * Main application flow for authenticated users.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainApplicationFlow(
    authState: AuthState.Authenticated,
    snackbarHostState: SnackbarHostState,
    authViewModel: AuthViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val sessionListViewModel: SessionListViewModel = koinViewModel()
    val chatViewModel: ChatViewModel = koinViewModel()
    val scope = rememberCoroutineScope()

    // Load initial data for authenticated user (improved data loading)
    LaunchedEffect(authState.userId) {
        sessionListViewModel.loadSessionsAndGroups()
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        OverflowTooltipText(
                            text = "${stringResource(Res.string.app_name)} - ${authState.username}"
                        )
                    },
                    actions = {
                        UserMenu(
                            username = authState.username,
                            onLogout = {
                                scope.launch { authViewModel.logout() }
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )
            },
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    NavigationBarItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat") },
                        label = { Text("Chat") },
                        selected = currentRoute == Chat.route,
                        onClick = {
                            navController.navigate(Chat) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentRoute == Settings.route,
                        onClick = {
                            navController.navigate(Settings) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    val visualsWithError = data.visuals as? SnackbarVisualsWithError
                    MainAppSnackbar(data, visualsWithError)
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                NavHost(navController = navController, startDestination = Chat) {
                    composable<Chat> {
                        ChatScreen(sessionListViewModel, chatViewModel)
                    }
                    composable<Settings> {
                        SettingsScreen()
                    }
                }
            }
        }
    }
}

/**
 * User menu dropdown for authenticated users.
 */
@Composable
private fun UserMenu(
    username: String,
    onLogout: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "User menu"
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // User info header
            DropdownMenuItem(
                text = {
                    Text(
                        text = username,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                onClick = { /* Future: navigate to profile */ },
                leadingIcon = {
                    Icon(Icons.Default.AccountCircle, contentDescription = null)
                }
            )

            HorizontalDivider()

            // Logout option
            DropdownMenuItem(
                text = { Text("Logout") },
                onClick = {
                    expanded = false
                    onLogout()
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                }
            )
        }
    }
}

/**
 * Snackbar for authentication screens.
 */
@Composable
private fun AuthSnackbar(
    data: SnackbarData,
    visualsWithError: SnackbarVisualsWithError?
) {
    val containerColor: Color
    val contentColor: Color
    val actionColor: Color
    val actionContentColor: Color
    val dismissActionContentColor: Color

    if (visualsWithError?.isError == true) {
        containerColor = MaterialTheme.colorScheme.errorContainer
        contentColor = MaterialTheme.colorScheme.onErrorContainer
        actionColor = MaterialTheme.colorScheme.error
        actionContentColor = MaterialTheme.colorScheme.error
        dismissActionContentColor = MaterialTheme.colorScheme.onErrorContainer
    } else {
        containerColor = MaterialTheme.colorScheme.inverseSurface
        contentColor = MaterialTheme.colorScheme.inverseOnSurface
        actionColor = MaterialTheme.colorScheme.inversePrimary
        actionContentColor = MaterialTheme.colorScheme.inversePrimary
        dismissActionContentColor = MaterialTheme.colorScheme.inverseOnSurface
    }

    Snackbar(
        snackbarData = data,
        modifier = Modifier.padding(12.dp),
        containerColor = containerColor,
        contentColor = contentColor,
        actionColor = actionColor,
        actionContentColor = actionContentColor,
        dismissActionContentColor = dismissActionContentColor
    )
}

/**
 * Snackbar for main application screens.
 */
@Composable
private fun MainAppSnackbar(
    data: SnackbarData,
    visualsWithError: SnackbarVisualsWithError?
) {
    // Same implementation as AuthSnackbar - could be extracted to shared component
    AuthSnackbar(data, visualsWithError)
}

/**
 * A custom SnackbarVisuals implementation with an additional `isError` flag.
 *
 * @param isError Whether the original event was an error (determines colors)
 * @param message The message to display in the Snackbar
 * @param actionLabel The label for the action button, if any
 * @param withDismissAction Whether to show a dismiss action (defaults to true)
 * @param duration The duration for which the Snackbar should be shown (defaults to SnackbarDuration.Short if no actionLabel, SnackbarDuration.Indefinite otherwise)
 */
data class SnackbarVisualsWithError(
    val isError: Boolean,
    override val message: String,
    override val actionLabel: String?,
    override val withDismissAction: Boolean = true,
    override val duration: SnackbarDuration =
        if (actionLabel != null) SnackbarDuration.Indefinite else SnackbarDuration.Short
) : SnackbarVisuals