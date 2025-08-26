package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import eu.torvian.chatbot.app.compose.common.OverflowTooltipText
import eu.torvian.chatbot.app.domain.events.GlobalError
import eu.torvian.chatbot.app.domain.events.GlobalSuccess
import eu.torvian.chatbot.app.domain.events.SnackbarInteractionEvent
import eu.torvian.chatbot.app.domain.navigation.Chat
import eu.torvian.chatbot.app.domain.navigation.Settings
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.action_retry
import eu.torvian.chatbot.app.generated.resources.app_name
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel
import eu.torvian.chatbot.app.viewmodel.SessionListViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.currentKoinScope
import org.koin.compose.viewmodel.koinViewModel

/**
 * The main application shell responsible for top-level layout and screen navigation.
 * Uses `Scaffold` for consistent Material Design structure and `NavHost` for navigation.
 * (E7.S2: Implement Base App Layout & ViewModel Integration with proper navigation)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val eventBus: EventBus = currentKoinScope().get()
    val sessionListViewModel: SessionListViewModel = koinViewModel()
    val chatViewModel: ChatViewModel = koinViewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Collect events from EventBus and show them in Snackbar
    LaunchedEffect(eventBus) {
        eventBus.events.collect { event ->
            if (event !is GlobalError && event !is GlobalSuccess) {
                return@collect
            }

            // Launch a new coroutine to handle the Snackbar display
            scope.launch {
                val message: String
                val actionLabel: String?
                val duration: SnackbarDuration

                when (event) {
                    is GlobalError -> {
                        message = event.message
                        actionLabel = if (event.isRetryable) getString(Res.string.action_retry) else null
                        duration = if (event.isRetryable) SnackbarDuration.Indefinite else SnackbarDuration.Long
                    }

                    is GlobalSuccess -> {
                        message = event.message
                        actionLabel = null
                        duration = SnackbarDuration.Short
                    }

                    else -> return@launch
                }

                // Show the Snackbar with custom visuals
                val visuals = SnackbarVisualsWithError(
                    isError = event is GlobalError,
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

    // Initial loading of sessions and groups on app startup
    LaunchedEffect(Unit) {
        sessionListViewModel.loadSessionsAndGroups()
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        OverflowTooltipText(
                            text = stringResource(Res.string.app_name),
                        )
                    },
                    actions = {
                        Button(
                            onClick = {
                                val targetRoute = if (currentRoute == Chat.name) Settings else Chat
                                // This is the recommended pattern for "tab-like" navigation
                                // It ensures that only one instance of a top-level screen exists
                                // at any time, and its state is saved and restored.
                                navController.navigate(targetRoute) {
                                    // Pop up to the start destination of the graph to
                                    // avoid building up a large stack of destinations
                                    // when selecting items from a bottom navigation bar.
                                    // This also ensures that the back button behavior
                                    // is predictable (usually exiting the app from the root tab).
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true // Save the state of the popped destinations
                                    }
                                    // Avoid multiple copies of the same destination when
                                    // reselecting the same item.
                                    launchSingleTop = true
                                    // Restore state when reselecting a previously selected item
                                    restoreState = true
                                }
                            }
                        ) {
                            Text(if (currentRoute == Chat.name) "Settings" else "Chat")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )
            },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    val visualsWithError = data.visuals as? SnackbarVisualsWithError

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
            }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                NavHost(navController = navController, startDestination = Chat) {
                    composable<Chat> { // backStackEntry ->
                        // this can be used to extract arguments from the route:
//                        val route: Chat = backStackEntry.toRoute()
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